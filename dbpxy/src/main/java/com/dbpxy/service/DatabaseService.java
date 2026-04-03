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
import com.dbpxy.grpc.DbpxyClient;
import com.dbpxy.jdbc.ConnectionProxy;
import com.dbpxy.logging.MDC;
import com.dbpxy.proto.*;
import com.dbpxy.util.DatabaseUtil;
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
    private static final String MDC_TRANSACTION_ID = "transaction.id";
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

    static String connectionStringHash(final ConnectionString connectionString) {
        final List<ConnectionStringProp> sortedPropList = new ArrayList<>(connectionString.getPropsList());
        sortedPropList.sort(Comparator.comparing(ConnectionStringProp::getName));

        final Hasher hasher = Hashing.sha256().newHasher()
                .putString(RANDOM_PASSPHRASE, StandardCharsets.UTF_8)
                .putChar('&');

        for (final ConnectionStringProp prop : sortedPropList) {
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
                        return Duration.ofMillis(ops.getTimeoutInMs()).plus(Duration.ofSeconds(1)).toNanos();
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
                    try (final MDC transactionIdMDC = new MDC(MDC_TRANSACTION_ID, ops.getTransaction())) {
                        log.debug("transaction cache eviction. cause: {}", cause);
                        ops.closeConnection();
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

        final Transaction transaction = Transaction.newBuilder()
                .setId(cryptoService.encrypt(transactionId))
                .setStatus(Transaction.Status.ACTIVE)
                .setNode(node)
                .build();

        try (final MDC transactionIdMDC = new MDC(MDC_TRANSACTION_ID, transaction)) {
            log.trace("beginTransaction() -> {}", transaction.getStatus());

            final DatabaseOperation ops = DatabaseOperation.builder()
                    .cryptoService(cryptoService)
                    .uniqueIdGenerator(uniqueIdGenerator)
                    .transaction(transaction)
                    .timeoutInMs(DatabaseUtil.sanitizeTimeoutInMs(config.getTimeoutInMs()))
                    .build();

            transactionCache.put(transactionId, ops);

            final ObjectPool<ConnectionProxy> connectionPool = connectionPoolCache.get(
                    connectionStringHash(config.getConnectionString()),
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
                        poolConfig.setTestOnBorrow(true);
                        poolConfig.setTestWhileIdle(true);

                        final Properties props = new Properties();
                        config.getConnectionString().getPropsList()
                                .forEach(prop -> props.put(prop.getName(), prop.getValue()));

                        final BasePooledObjectFactory<ConnectionProxy> poolFactory = new BasePooledObjectFactory<>() {

                            @Override
                            public ConnectionProxy create() throws Exception {
                                return new ConnectionProxy(DriverManager.getConnection(config.getConnectionString().getUrl(), props));
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

            ops.openConnection(connectionPool, taskExecutor);

            final OffsetDateTime creationTime = ops.beginTransaction(config);

            responseObserver.onNext(transaction.toBuilder()
                    .setCreation(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(creationTime))
                    .setExpiration(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(creationTime.plus(Duration.ofMillis(config.getTimeoutInMs()))))
                    .build());
            responseObserver.onCompleted();
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .augmentDescription(node)
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void commitTransaction(
            final Transaction transaction,
            final StreamObserver<Transaction> responseObserver) {
        try (final MDC transactionIdMDC = new MDC(MDC_TRANSACTION_ID, transaction)) {

            if (shouldNotExecuteOnThisNode(transaction)) {
                dbpxyClient.invoke(transaction.getNode(), dbpxyGrpcProperties.getPort(), blockingStub -> {
                    final Transaction result = blockingStub.commitTransaction(transaction);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(transaction)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));

            if (ops.getTransaction().getStatus() != Transaction.Status.ACTIVE) {
                throw new IllegalStateException("Transaction is not active");
            }

            final boolean committed = ops.commitTransaction();

            final Transaction result = ops.getTransaction().toBuilder()
                    .setStatus(committed ? Transaction.Status.COMMITTED : Transaction.Status.UNKNOWN)
                    .build();

            ops.setTransaction(result);

            log.trace("commitTransaction() -> {}", result.getStatus());

            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (final IllegalArgumentException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .augmentDescription(node)
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (final IllegalStateException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .augmentDescription(node)
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .augmentDescription(node)
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
        try (final MDC transactionIdMDC = new MDC(MDC_TRANSACTION_ID, transaction)) {

            if (shouldNotExecuteOnThisNode(transaction)) {
                dbpxyClient.invoke(transaction.getNode(), dbpxyGrpcProperties.getPort(), blockingStub -> {
                    final Transaction result = blockingStub.rollbackTransaction(transaction);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(transaction)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));

            if (ops.getTransaction().getStatus() != Transaction.Status.ACTIVE) {
                throw new IllegalStateException("Transaction is not active");
            }

            final boolean rolledBack = ops.rollbackTransaction();

            final Transaction result = ops.getTransaction().toBuilder()
                    .setStatus(rolledBack ? Transaction.Status.ROLLED_BACK : Transaction.Status.UNKNOWN)
                    .build();

            ops.setTransaction(result);

            log.trace("rollbackTransaction() -> {}", result.getStatus());

            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (final IllegalArgumentException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .augmentDescription(node)
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (final IllegalStateException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .augmentDescription(node)
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .augmentDescription(node)
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
        try (final MDC transactionIdMDC = new MDC(MDC_TRANSACTION_ID, config.getTransaction())) {

            if (shouldNotExecuteOnThisNode(config.getTransaction())) {
                dbpxyClient.invoke(config.getTransaction().getNode(), dbpxyGrpcProperties.getPort(), blockingStub -> {
                    final ExecuteResult result = blockingStub.executeTx(config);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(config.getTransaction())
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
            final ExecuteResult result = ops.execute(config.getExecuteConfig());
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .augmentDescription(node)
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void queryTx(
            final QueryTxConfig config,
            final StreamObserver<QueryResult> responseObserver) {
        try (final MDC transactionIdMDC = new MDC(MDC_TRANSACTION_ID, config.getTransaction())) {

            if (shouldNotExecuteOnThisNode(config.getTransaction())) {
                dbpxyClient.invoke(config.getTransaction().getNode(), dbpxyGrpcProperties.getPort(), blockingStub -> {
                    final QueryResult result = blockingStub.queryTx(config);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(config.getTransaction())
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
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .augmentDescription(node)
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void next(
            final NextConfig config,
            final StreamObserver<QueryResult> responseObserver) {
        try (final MDC transactionIdMDC = new MDC(MDC_TRANSACTION_ID, config.getTransaction())) {

            if (shouldNotExecuteOnThisNode(config.getTransaction())) {
                dbpxyClient.invoke(config.getTransaction().getNode(), dbpxyGrpcProperties.getPort(), blockingStub -> {
                    final QueryResult result = blockingStub.next(config);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(config.getTransaction())
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
            final QueryResult result = ops.next(config);
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .augmentDescription(node)
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
        try (final MDC transactionIdMDC = new MDC(MDC_TRANSACTION_ID, config.getTransaction())) {

            if (shouldNotExecuteOnThisNode(config.getTransaction())) {
                dbpxyClient.invoke(config.getTransaction().getNode(), dbpxyGrpcProperties.getPort(), blockingStub -> {
                    final Empty result = blockingStub.closeResultSet(config);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            getDatabaseOperationByTransaction(config.getTransaction())
                    .ifPresent(ops -> ops.closeResultSet(config));

            responseObserver.onNext(Empty.newBuilder().build());
            responseObserver.onCompleted();
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .augmentDescription(node)
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    private boolean shouldNotExecuteOnThisNode(final Transaction transaction) {
        final boolean matches = Objects.equals(transaction.getNode(), node);
        if (!matches) {
            log.warn("oh no! incorrect node {}", node);
        }
        return !matches;
    }

    private Optional<DatabaseOperation> getDatabaseOperationByTransaction(final Transaction transaction) {
        return Optional.ofNullable(transaction)
                .map(it -> cryptoService.decrypt(it.getId()))
                .map(transactionCache::getIfPresent);
    }

    private void closeDatabaseOperationByTransaction(final Transaction transaction) {
        Optional.ofNullable(transaction)
                .map(it -> cryptoService.decrypt(it.getId()))
                .ifPresent(transactionCache::invalidate);
    }
}
