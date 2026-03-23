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
import com.dbpxy.grpc.DbpxyClient;
import com.dbpxy.jdbc.ConnectionProxy;
import com.dbpxy.proto.*;
import com.dbpxy.stormpot.ConnectionExpiration;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.google.common.hash.Hashing;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.grpc.server.service.GrpcService;
import stormpot.Allocator;
import stormpot.Pool;
import stormpot.Slot;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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

    private final Cache<String, Pool<ConnectionProxy>> connectionPoolCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofDays(1))
            .<String, Pool<ConnectionProxy>>removalListener((key, pool, cause) -> {
                if (pool != null) {
                    pool.shutdown();
                }
            })
            .build();

    private final Cache<String, DatabaseOperation> transactionCache = Caffeine.newBuilder()
            .expireAfter(new Expiry<String, DatabaseOperation>() {
                @Override
                public long expireAfterCreate(
                        final String transactionId,
                        final DatabaseOperation ops,
                        final long currentTime) {
                    return Duration.ofMillis(ops.getTimeoutInMs()).toNanos() + Duration.ofSeconds(1).toNanos();
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
            .removalListener((transactionId, ops, cause) -> {
                if (ops != null) {
                    try {
                        MDC.put(MDC_TRANSACTION_ID, DatabaseUtil.getMaskedId(ops.getTransaction().getId()) + "@" + ops.getTransaction().getNode());
                        log.debug("cache eviction");
                        ops.closeConnection();
                    } finally {
                        MDC.remove(MDC_TRANSACTION_ID);
                    }
                }
            })
            .build();
    private final DbpxyGrpcProperties dbpxyGrpcProperties;
    private final DbpxyClient dbpxyClient;
    private final CryptoService cryptoService;
    private final UniqueIdGenerator uniqueIdGenerator;
    private final String node;
    private final int poolTargetSize;
    private final long poolMaxAgeInMs;

    private final ExecutorService taskExecutor = Executors.newCachedThreadPool(ThreadFactory.builder().prefix("dbpxy-task-").build());

    public DatabaseService(
            final DbpxyGrpcProperties dbpxyGrpcProperties,
            final DbpxyClient dbpxyClient,
            final CryptoService cryptoService,
            final UniqueIdGenerator uniqueIdGenerator,
            @org.springframework.beans.factory.annotation.Value("${app.node}") final String node,
            @org.springframework.beans.factory.annotation.Value("${app.stormpot.target-size:5}") final int poolTargetSize,
            @org.springframework.beans.factory.annotation.Value("${app.stormpot.max-age-ms:60000}") final long poolMaxAgeInMs
    ) {
        log.info("hello from dbpxy on {}", node);
        this.dbpxyGrpcProperties = dbpxyGrpcProperties;
        this.dbpxyClient = dbpxyClient;
        this.cryptoService = cryptoService;
        this.uniqueIdGenerator = uniqueIdGenerator;
        this.node = node;
        this.poolTargetSize = poolTargetSize;
        this.poolMaxAgeInMs = poolMaxAgeInMs;
    }

    static String connectionStringHash(final ConnectionString connectionString) {
        final List<ConnectionStringProp> propsList = new ArrayList<>(connectionString.getPropsList());
        propsList.sort(Comparator.comparing(ConnectionStringProp::getName));

        final String urlParams = propsList.stream()
                .map(prop -> prop.getName() + "=" + prop.getValue())
                .collect(Collectors.joining("&"));

        return connectionString.getUrl() + "#" + Hashing.sha512().hashString(urlParams, StandardCharsets.UTF_8);
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
        try {
            MDC.put(MDC_TRANSACTION_ID, DatabaseUtil.getMaskedId(transaction.getId()) + "@" + transaction.getNode());
            log.debug("beginTransaction() -> {}", transaction.getStatus());

            final DatabaseOperation ops = DatabaseOperation.builder()
                    .cryptoService(cryptoService)
                    .uniqueIdGenerator(uniqueIdGenerator)
                    .transaction(transaction)
                    .timeoutInMs(DatabaseUtil.sanitizeTimeoutInMs(config.getTimeoutInMs()))
                    .build();

            transactionCache.put(transactionId, ops);

            final Pool<ConnectionProxy> connectionPool = connectionPoolCache.get(
                    connectionStringHash(config.getConnectionString()),
                    ignored -> Pool.from(new Allocator<ConnectionProxy>() {
                                @Override
                                public ConnectionProxy allocate(final Slot slot) throws Exception {
                                    final Properties props = new Properties();
                                    config.getConnectionString().getPropsList()
                                            .forEach(prop -> props.put(prop.getName(), prop.getValue()));
                                    return new ConnectionProxy(slot, DriverManager.getConnection(config.getConnectionString().getUrl(), props));
                                }

                                @Override
                                public void deallocate(final ConnectionProxy proxy) throws Exception {
                                    proxy.getConnection().close();
                                }
                            })
                            .setSize(poolTargetSize)
                            .setExpiration(new ConnectionExpiration(Duration.ofMillis(poolMaxAgeInMs).toMillis()))
                            .build());

            ops.openConnection(connectionPool, taskExecutor);
            ops.beginTransaction(config);

            responseObserver.onNext(transaction);
            responseObserver.onCompleted();
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .augmentDescription(node)
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } finally {
            MDC.remove(MDC_TRANSACTION_ID);
        }
    }

    @Override
    public void commitTransaction(
            final Transaction transaction,
            final StreamObserver<Transaction> responseObserver) {
        try {
            MDC.put(MDC_TRANSACTION_ID, DatabaseUtil.getMaskedId(transaction.getId()) + "@" + transaction.getNode());

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

            log.debug("commitTransaction() -> {}", result.getStatus());

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
            MDC.remove(MDC_TRANSACTION_ID);
        }
    }

    @Override
    public void rollbackTransaction(
            final Transaction transaction,
            final StreamObserver<Transaction> responseObserver) {
        try {
            MDC.put(MDC_TRANSACTION_ID, DatabaseUtil.getMaskedId(transaction.getId()) + "@" + transaction.getNode());

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

            log.debug("rollbackTransaction() -> {}", result.getStatus());

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
            MDC.remove(MDC_TRANSACTION_ID);
        }
    }

    @Override
    public void executeTx(
            final ExecuteTxConfig config,
            final StreamObserver<ExecuteResult> responseObserver) {
        try {
            MDC.put(MDC_TRANSACTION_ID, DatabaseUtil.getMaskedId(config.getTransaction().getId()) + "@" + config.getTransaction().getNode());

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
        } finally {
            MDC.remove(MDC_TRANSACTION_ID);
        }
    }

    @Override
    public void queryTx(
            final QueryTxConfig config,
            final StreamObserver<QueryResult> responseObserver) {
        try {
            MDC.put(MDC_TRANSACTION_ID, DatabaseUtil.getMaskedId(config.getTransaction().getId()) + "@" + config.getTransaction().getNode());

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
            result = ops.next(NextConfig.newBuilder()
                    .setTransaction(config.getTransaction())
                    .setQueryResultId(result.getId())
                    .build());
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .augmentDescription(node)
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } finally {
            MDC.remove(MDC_TRANSACTION_ID);
        }
    }

    @Override
    public void next(
            final NextConfig config,
            final StreamObserver<QueryResult> responseObserver) {
        try {
            MDC.put(MDC_TRANSACTION_ID, DatabaseUtil.getMaskedId(config.getTransaction().getId()) + "@" + config.getTransaction().getNode());

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
        } finally {
            MDC.remove(MDC_TRANSACTION_ID);
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
        try {
            MDC.put(MDC_TRANSACTION_ID, DatabaseUtil.getMaskedId(config.getTransaction().getId()) + "@" + config.getTransaction().getNode());

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
        } finally {
            MDC.remove(MDC_TRANSACTION_ID);
        }
    }

    private boolean shouldNotExecuteOnThisNode(final Transaction transaction) {
        final boolean matches = Objects.equals(transaction.getNode(), node);
        if (!matches) {
            log.debug("oh no! incorrect node {}", node);
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
                .filter(it -> Objects.equals(it.getNode(), node))
                .map(it -> cryptoService.decrypt(it.getId()))
                .ifPresent(transactionCache::invalidate);
    }
}
