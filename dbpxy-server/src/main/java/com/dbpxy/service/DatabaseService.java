package com.dbpxy.service;

/*-
 * #%L
 * dbpxy
 * %%
 * Copyright (C) 2025 Fernando Lemes Povoa
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.dbpxy.config.DbpxyGrpcProperties;
import com.dbpxy.config.DbpxyPoolProperties;
import com.dbpxy.exception.UnsupportedInReadOnlyModeException;
import com.dbpxy.exception.UnsupportedInWriteOnlyModeException;
import com.dbpxy.grpc.DbpxyClient;
import com.dbpxy.jdbc.ConnectionProxy;
import com.dbpxy.logging.MDC;
import com.dbpxy.proto.*;
import com.dbpxy.util.DatabaseUtils;
import com.dbpxy.util.UniqueIdGenerator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultEvictionPolicy;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.grpc.server.service.GrpcService;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

@Slf4j
@GrpcService
@RegisterReflectionForBinding({
        BeginTransactionConfig.class,
        ExecuteTxConfig.class,
        QueryTxConfig.class,
        NextConfig.class,
        Transaction.class,
        ExecuteResult.class,
        QueryResult.class,
        Empty.class
})
public class DatabaseService extends DbpxyGrpc.DbpxyImplBase {
    private static final String MDC_TRANSACTION_ID = "dbpxy.tx.id";
    private static final String RANDOM_PASSPHRASE = RandomStringUtils.secure().next(30, true, true);

    private final DbpxyGrpcProperties dbpxyGrpcProperties;
    private final DbpxyPoolProperties dbpxyPoolProperties;
    private final DbpxyClient dbpxyClient;
    private final CryptoService cryptoService;
    private final UniqueIdGenerator uniqueIdGenerator;
    private final String node;

    private ExecutorService taskExecutor;
    private Cache<String, ObjectPool<ConnectionProxy>> connectionPoolCache;
    private Cache<String, DatabaseOperation> transactionCache;

    public DatabaseService(
            final DbpxyGrpcProperties dbpxyGrpcProperties,
            final DbpxyPoolProperties dbpxyPoolProperties,
            final DbpxyClient dbpxyClient,
            final CryptoService cryptoService,
            final UniqueIdGenerator uniqueIdGenerator,
            @org.springframework.beans.factory.annotation.Value("${app.node}") final String node) {
        log.info("hello from dbpxy on {}", node);
        this.dbpxyGrpcProperties = dbpxyGrpcProperties;
        this.dbpxyPoolProperties = dbpxyPoolProperties;
        this.dbpxyClient = dbpxyClient;
        this.cryptoService = cryptoService;
        this.uniqueIdGenerator = uniqueIdGenerator;
        this.node = node;
    }

    static String connectionStringHash(final DatabaseOperationProp.ConnectionString connectionString) {
        final List<DatabaseOperationProp.ConnectionStringProp> sortedPropList = new ArrayList<>(connectionString.getProps());
        sortedPropList.sort(Comparator.comparing(DatabaseOperationProp.ConnectionStringProp::getName));

        final Hasher hasher = Hashing.sha256().newHasher()
                .putString(RANDOM_PASSPHRASE, StandardCharsets.UTF_8)
                .putChar('&');

        for (final DatabaseOperationProp.ConnectionStringProp prop : sortedPropList) {
            hasher.putString(prop.getName(), StandardCharsets.UTF_8)
                    .putChar('=')
                    .putString(prop.getValue(), StandardCharsets.UTF_8)
                    .putChar('&');
        }

        return connectionString.getUrl() + "#" + hasher.hash();
    }

    @PostConstruct
    public void onInit() {
        this.taskExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dbpxy-", 0).factory());

        this.connectionPoolCache = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofDays(1))
                .removalListener((final String ignored, final ObjectPool<ConnectionProxy> pool, final RemovalCause cause) -> {
                    log.debug("connection pool cache eviction. active: {}, idle: {}, cause: {}", pool.getNumActive(), pool.getNumIdle(), cause);
                    try {
                        pool.clear();
                    } catch (final Exception e) {
                        log.error("failed to clear connection pool", e);
                    }
                    pool.close();
                })
                .build();

        this.transactionCache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, DatabaseOperation>() {
                    @Override
                    public long expireAfterCreate(
                            final String transactionId,
                            final DatabaseOperation ops,
                            final long currentTime) {
                        return Duration.ofMillis(ops.getTimeoutInMs()).toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(
                            final String transactionId,
                            final DatabaseOperation ops,
                            final long currentTime,
                            final long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(
                            final String transactionId,
                            final DatabaseOperation ops,
                            final long currentTime,
                            final long currentDuration) {
                        return currentDuration;
                    }
                })
                .removalListener((final String ignored, final DatabaseOperation ops, final RemovalCause cause) -> {
                    try (final MDC _ = new MDC(MDC_TRANSACTION_ID, ops.getTransaction())) {
                        log.debug("transaction cache eviction. cause: {}", cause);
                        ops.closeConnection();
                        Stream.of(
                                        ops.getTransaction().getId(),
                                        ops.getTransaction().getReadOnlyId(),
                                        ops.getTransaction().getReadWriteId(),
                                        ops.getTransaction().getWriteOnlyId()
                                )
                                .filter(StringUtils::isNotEmpty)
                                .forEach(transactionCache::invalidate);
                    }
                })
                .build();
    }

    public void onShutdown() {
        transactionCache.invalidateAll();
        connectionPoolCache.invalidateAll();
        taskExecutor.shutdownNow();
        log.info("goodbye from dbpxy on {}", node);
    }

    @Override
    public void beginTransaction(
            final BeginTransactionConfig config,
            final StreamObserver<Transaction> responseObserver) {
        final String transactionId = uniqueIdGenerator.globalUUID(Transaction.class.getName());
        final String readOnlyTransactionId = uniqueIdGenerator.globalUUID(Transaction.class.getName());
        final String readWriteTransactionId = uniqueIdGenerator.globalUUID(Transaction.class.getName());
        final String writeOnlyTransactionId = uniqueIdGenerator.globalUUID(Transaction.class.getName());

        final Transaction transaction = Transaction.newBuilder()
                .setId(cryptoService.encrypt(transactionId))
                .setReadOnlyId(cryptoService.encrypt(readOnlyTransactionId))
                .setReadWriteId(cryptoService.encrypt(readWriteTransactionId))
                .setWriteOnlyId(cryptoService.encrypt(writeOnlyTransactionId))
                .setStatus(Transaction.Status.NOT_STARTED)
                .setNode(node)
                .build();

        try (final MDC _ = new MDC(MDC_TRANSACTION_ID, transaction)) {

            final DatabaseOperation ops = DatabaseOperationImpl.builder()
                    .databaseOperationProp(DatabaseOperationProp.builder()
                            .timeoutInMs(config.getTimeoutInMs())
                            .autoCommit(config.getAutoCommit())
                            .readOnly(config.getReadOnly())
                            .connectionString(DatabaseOperationProp.ConnectionString.builder()
                                    .url(config.getConnectionString().getUrl().toCharArray())
                                    .props(config.getConnectionString().getPropsList().stream()
                                            .map(prop -> DatabaseOperationProp.ConnectionStringProp.builder()
                                                    .name(prop.getName().toCharArray())
                                                    .value(prop.getValue().toCharArray())
                                                    .build())
                                            .toList())
                                    .build())
                            .activation(Stream.of(DatabaseOperationProp.Activation.values())
                                    .filter(e -> Strings.CS.equals(e.name(), config.getActivation().name()))
                                    .findFirst()
                                    .orElse(DatabaseOperationProp.Activation.EAGER))
                            .build())
                    .cryptoService(cryptoService)
                    .uniqueIdGenerator(uniqueIdGenerator)
                    .transaction(transaction)
                    .timeoutInMs(DatabaseUtils.sanitizeTimeoutInMs(config.getTimeoutInMs()))
                    .build();

            transactionCache.put(transactionId, ops);
            transactionCache.put(readOnlyTransactionId, new DatabaseReadOnlyOperation(ops));
            transactionCache.put(readWriteTransactionId, new DatabaseReadWriteOperation(ops));
            transactionCache.put(writeOnlyTransactionId, new DatabaseWriteOnlyOperation(ops));

            final OffsetDateTime creationTime = OffsetDateTime.now();

            ops.setTransaction(ops.getTransaction().toBuilder()
                    .setCreation(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(creationTime))
                    .setExpiration(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(creationTime.plus(Duration.ofMillis(ops.getTimeoutInMs()))))
                    .build());

            if (config.getActivation() == BeginTransactionConfig.Activation.EAGER) {
                activateTransaction(ops);
            }

            log.trace("beginTransaction() -> {}", ops.getTransaction().getStatus());

            responseObserver.onNext(ops.getTransaction());
            responseObserver.onCompleted();
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void commitTransaction(
            final Transaction transaction,
            final StreamObserver<Transaction> responseObserver) {
        try (final MDC _ = new MDC(MDC_TRANSACTION_ID, transaction)) {

            if (shouldNotExecuteOnThisNode(transaction)) {
                dbpxyClient.invoke(transaction.getNode(), dbpxyGrpcProperties.getPort(), blockingStub -> {
                    final Transaction result = blockingStub.commitTransaction(transaction);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(transaction, false)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));

            if (!List.of(Transaction.Status.NOT_STARTED, Transaction.Status.ACTIVE).contains(ops.getTransaction().getStatus())) {
                throw new IllegalStateException("Transaction is not active");
            }

            final boolean committed = (ops.getTransaction().getStatus() == Transaction.Status.ACTIVE) && ops.commitTransaction();

            final Transaction result = ops.getTransaction().toBuilder()
                    .setStatus(committed ? Transaction.Status.COMMITTED : Transaction.Status.UNKNOWN)
                    .build();

            ops.setTransaction(result);

            log.trace("commitTransaction() -> {}", result.getStatus());

            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (final IllegalArgumentException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (final IllegalStateException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } finally {
            closeDatabaseOperationByTransaction(transaction);
        }
    }

    @Override
    public void rollbackTransaction(
            final Transaction transaction,
            final StreamObserver<Transaction> responseObserver) {
        try (final MDC _ = new MDC(MDC_TRANSACTION_ID, transaction)) {

            if (shouldNotExecuteOnThisNode(transaction)) {
                dbpxyClient.invoke(transaction.getNode(), dbpxyGrpcProperties.getPort(), blockingStub -> {
                    final Transaction result = blockingStub.rollbackTransaction(transaction);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(transaction, false)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));

            if (!List.of(Transaction.Status.NOT_STARTED, Transaction.Status.ACTIVE).contains(ops.getTransaction().getStatus())) {
                throw new IllegalStateException("Transaction is not active");
            }

            final boolean rolledBack = (ops.getTransaction().getStatus() == Transaction.Status.ACTIVE) && ops.rollbackTransaction();

            final Transaction result = ops.getTransaction().toBuilder()
                    .setStatus(rolledBack ? Transaction.Status.ROLLED_BACK : Transaction.Status.UNKNOWN)
                    .build();

            ops.setTransaction(result);

            log.trace("rollbackTransaction() -> {}", result.getStatus());

            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (final IllegalArgumentException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (final IllegalStateException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } finally {
            closeDatabaseOperationByTransaction(transaction);
        }
    }

    @Override
    public void executeTx(
            final ExecuteTxConfig config,
            final StreamObserver<ExecuteResult> responseObserver) {
        try (final MDC _ = new MDC(MDC_TRANSACTION_ID, config.getTransaction())) {

            if (shouldNotExecuteOnThisNode(config.getTransaction())) {
                dbpxyClient.invoke(config.getTransaction().getNode(), dbpxyGrpcProperties.getPort(), blockingStub -> {
                    final ExecuteResult result = blockingStub.executeTx(config);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(config.getTransaction(), true)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
            final ExecuteResult result = ops.execute(config.getExecuteConfig());
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (final UnsupportedInReadOnlyModeException e) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("READ_ONLY_MODE")
                    .withCause(e)
                    .asRuntimeException());
        } catch (final IllegalArgumentException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void queryTx(
            final QueryTxConfig config,
            final StreamObserver<QueryResult> responseObserver) {
        try (final MDC _ = new MDC(MDC_TRANSACTION_ID, config.getTransaction())) {

            if (shouldNotExecuteOnThisNode(config.getTransaction())) {
                dbpxyClient.invoke(config.getTransaction().getNode(), dbpxyGrpcProperties.getPort(), blockingStub -> {
                    final QueryResult result = blockingStub.queryTx(config);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(config.getTransaction(), true)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
            QueryResult result = ops.query(config.getQueryConfig());
            // Pre-fetches the first page and allows for more pages to be fetched later.
            if (result.getHasNext()) {
                result = ops.next(NextConfig.newBuilder()
                        .setTransaction(config.getTransaction())
                        .setQueryResultId(result.getId())
                        .build());
            }
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (final UnsupportedInWriteOnlyModeException e) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("WRITE_ONLY_MODE")
                    .withCause(e)
                    .asRuntimeException());
        } catch (final IllegalArgumentException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void next(
            final NextConfig config,
            final StreamObserver<QueryResult> responseObserver) {
        try (final MDC _ = new MDC(MDC_TRANSACTION_ID, config.getTransaction())) {

            if (shouldNotExecuteOnThisNode(config.getTransaction())) {
                dbpxyClient.invoke(config.getTransaction().getNode(), dbpxyGrpcProperties.getPort(), blockingStub -> {
                    final QueryResult result = blockingStub.next(config);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(config.getTransaction(), false)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
            final QueryResult result = ops.next(config);
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (final UnsupportedInWriteOnlyModeException e) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("WRITE_ONLY_MODE")
                    .withCause(e)
                    .asRuntimeException());
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void closeStatement(
            final Empty empty,
            final StreamObserver<Empty> responseObserver) {
        responseObserver.onNext(empty);
        responseObserver.onCompleted();
    }

    @Override
    public void closeResultSet(
            final NextConfig config,
            final StreamObserver<Empty> responseObserver) {
        try (final MDC _ = new MDC(MDC_TRANSACTION_ID, config.getTransaction())) {

            if (shouldNotExecuteOnThisNode(config.getTransaction())) {
                dbpxyClient.invoke(config.getTransaction().getNode(), dbpxyGrpcProperties.getPort(), blockingStub -> {
                    final Empty result = blockingStub.closeResultSet(config);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final Optional<DatabaseOperation> maybeOps = getDatabaseOperationByTransaction(config.getTransaction(), false);
            if (maybeOps.isPresent()) {
                maybeOps.get().closeResultSet(config);
            }

            responseObserver.onNext(Empty.newBuilder().build());
            responseObserver.onCompleted();
        } catch (final UnsupportedInWriteOnlyModeException e) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("WRITE_ONLY_MODE")
                    .withCause(e)
                    .asRuntimeException());
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @SuppressWarnings("java:S2445")
    private DatabaseOperation activateTransaction(final DatabaseOperation ops) {
        final DatabaseOperation delegate = ops.getDelegate();

        if (delegate.getTransaction().getStatus() == Transaction.Status.NOT_STARTED) {
            synchronized (delegate) {
                if (delegate.getTransaction().getStatus() == Transaction.Status.NOT_STARTED) {

                    try (final MDC _ = new MDC(MDC_TRANSACTION_ID, delegate.getTransaction())) {
                        log.trace("activateTransaction() -> {}", delegate.getTransaction().getStatus());

                        final ObjectPool<ConnectionProxy> connectionPool = connectionPoolCache.get(
                                connectionStringHash(delegate.getDatabaseOperationProp().getConnectionString()),
                                ignored -> {
                                    final GenericObjectPoolConfig<ConnectionProxy> poolConfig = new GenericObjectPoolConfig<>();
                                    poolConfig.setMinEvictableIdleDuration(Duration.ofMillis(dbpxyPoolProperties.getMaxIdleAgeMs()));
                                    poolConfig.setTimeBetweenEvictionRuns(Duration.ofMillis(dbpxyPoolProperties.getMaxIdleAgeMs() / 2));
                                    poolConfig.setEvictionPolicy(new DefaultEvictionPolicy<>());
                                    poolConfig.setBlockWhenExhausted(true);
                                    poolConfig.setMaxWait(Duration.ofMillis(dbpxyPoolProperties.getMaxWaitMs()));
                                    poolConfig.setMaxTotal(dbpxyPoolProperties.getMaxTotalSize());
                                    poolConfig.setMaxIdle(dbpxyPoolProperties.getMaxIdleSize());
                                    poolConfig.setMinIdle(dbpxyPoolProperties.getMinIdleSize());
                                    poolConfig.setTestOnCreate(true);
                                    poolConfig.setTestOnBorrow(true);
                                    poolConfig.setTestOnReturn(true);
                                    poolConfig.setTestWhileIdle(true);

                                    final Properties props = new Properties();
                                    delegate.getDatabaseOperationProp().getConnectionString().getProps()
                                            .forEach(prop -> props.put(prop.getName(), prop.getValue()));

                                    final BasePooledObjectFactory<ConnectionProxy> poolFactory = new BasePooledObjectFactory<>() {

                                        @Override
                                        public ConnectionProxy create() throws Exception {
                                            return new ConnectionProxy(DriverManager.getConnection(delegate.getDatabaseOperationProp().getConnectionString().getUrl(), props));
                                        }

                                        @Override
                                        public void destroyObject(final PooledObject<ConnectionProxy> p) throws Exception {
                                            p.getObject().getConnection().close();
                                        }

                                        @Override
                                        public boolean validateObject(final PooledObject<ConnectionProxy> p) {
                                            try {
                                                return p.getObject().isValid(1);
                                            } catch (final SQLException e) {
                                                return false;
                                            }
                                        }

                                        @Override
                                        public PooledObject<ConnectionProxy> wrap(final ConnectionProxy connection) {
                                            return new DefaultPooledObject<>(connection);
                                        }
                                    };

                                    return new GenericObjectPool<>(poolFactory, poolConfig);
                                });

                        delegate.openConnection(connectionPool, taskExecutor);
                        delegate.beginTransaction(delegate.getDatabaseOperationProp().toBuilder()
                                .timeoutInMs(transactionCache.policy().expireVariably()
                                        .flatMap(policy -> policy.getExpiresAfter(cryptoService.decrypt(delegate.getTransaction().getId())))
                                        .map(Duration::toMillis)
                                        .orElseThrow()
                                )
                                .build());
                        delegate.setTransaction(delegate.getTransaction().toBuilder()
                                .setStatus(Transaction.Status.ACTIVE)
                                .build());

                        log.trace("activateTransaction() -> {}", delegate.getTransaction().getStatus());
                    }
                }
            }
        }
        return ops;
    }

    private boolean shouldNotExecuteOnThisNode(final Transaction transaction) {
        final boolean matches = Objects.equals(transaction.getNode(), node);
        if (!matches) {
            log.warn("oh no! incorrect node {}", node);
        }
        return !matches;
    }

    private Optional<DatabaseOperation> getDatabaseOperationByTransaction(
            final Transaction transaction,
            final boolean shouldActivate) {
        return Optional.ofNullable(transaction)
                .map(it -> cryptoService.decrypt(it.getId()))
                .map(transactionCache::getIfPresent)
                .map(ops -> shouldActivate ? activateTransaction(ops) : ops);
    }

    private void closeDatabaseOperationByTransaction(final Transaction transaction) {
        Optional.ofNullable(transaction)
                .map(it -> cryptoService.decrypt(it.getId()))
                .ifPresent(transactionCache::invalidate);
    }
}
