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

import com.dbpxy.grpc.DbpxyClient;
import com.dbpxy.proto.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.openjpa.lib.jdbc.SQLFormatter;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DatabaseService extends DbpxyGrpc.DbpxyImplBase {
    private final ConcurrentHashMap<String, DatabaseOperation> transactionMap = new ConcurrentHashMap<>();
    private final DbpxyClient dbpxyClient;
    private final CryptoService cryptoService;
    private final UniqueIdGenerator uniqueIdGenerator;
    private final SQLFormatter defaultSqlFormatter;
    private final String node;

    public DatabaseService(
            final DbpxyClient dbpxyClient,
            final CryptoService cryptoService,
            final UniqueIdGenerator uniqueIdGenerator,
            @org.springframework.beans.factory.annotation.Value("${app.node}") final String node
    ) {
        log.info("hello from dbpxy node {}", node);

        this.dbpxyClient = dbpxyClient;
        this.cryptoService = cryptoService;
        this.uniqueIdGenerator = uniqueIdGenerator;
        this.node = node;

        final SQLFormatter sqlFormatter = new SQLFormatter();
        sqlFormatter.setClauseIndent("");
        sqlFormatter.setDoubleSpace(false);
        sqlFormatter.setLineLength(Integer.MAX_VALUE);
        sqlFormatter.setMultiLine(false);
        sqlFormatter.setNewline("");
        sqlFormatter.setWrapIndent("");
        this.defaultSqlFormatter = sqlFormatter;
    }

    @Override
    public void beginTransaction(
            final BeginTransactionConfig config,
            final StreamObserver<Transaction> responseObserver) {
        final String transactionId = uniqueIdGenerator.generate(Transaction.class);
        final Transaction transaction = Transaction.newBuilder()
                .setId(cryptoService.encrypt(transactionId))
                .setStatus(Transaction.Status.ACTIVE)
                .setNode(node)
                .build();

        MDC.put("transaction.id", DatabaseUtil.getMaskedId(transaction.getId()) + "@" + transaction.getNode());
        log.debug("beginTransaction(timeout: {}) -> {}", config.getTimeout(), transaction.getStatus());

        final DatabaseOperation ops = DatabaseOperation.builder()
                .cryptoService(cryptoService)
                .uniqueIdGenerator(uniqueIdGenerator)
                .connectionString(config.getConnectionString())
                .sqlFormatter(defaultSqlFormatter)
                .transaction(transaction)
                .build();

        transactionMap.put(transactionId, ops);

        try {
            ops.openConnection();
            ops.beginTransaction(config);

            responseObserver.onNext(transaction);
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
        try {
            MDC.put("transaction.id", DatabaseUtil.getMaskedId(transaction.getId()) + "@" + transaction.getNode());

            if (shouldNotExecuteOnThisNode(transaction)) {
                dbpxyClient.invoke(transaction.getNode(), blockingStub -> {
                    final Transaction result = blockingStub.commitTransaction(transaction);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(transaction)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));

            if (ops.getTransaction().getStatus() != Transaction.Status.ACTIVE) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("Transaction is not active")
                        .asRuntimeException());
                return;
            }

            final boolean committed = ops.commitTransaction();

            final Transaction result = ops.getTransaction().toBuilder()
                    .setStatus(committed ? Transaction.Status.COMMITTED : Transaction.Status.UNKNOWN)
                    .build();

            ops.setTransaction(result);

            log.debug("commitTransaction() -> {}", result.getStatus());

            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .augmentDescription(node)
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } finally {
            deleteDatabaseOperationByTransaction(transaction);
        }
    }

    @Override
    public void rollbackTransaction(
            final Transaction transaction,
            final StreamObserver<Transaction> responseObserver) {
        try {
            MDC.put("transaction.id", DatabaseUtil.getMaskedId(transaction.getId()) + "@" + transaction.getNode());

            if (shouldNotExecuteOnThisNode(transaction)) {
                dbpxyClient.invoke(transaction.getNode(), blockingStub -> {
                    final Transaction result = blockingStub.rollbackTransaction(transaction);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(transaction)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));

            if (ops.getTransaction().getStatus() != Transaction.Status.ACTIVE) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("Transaction is not active")
                        .asRuntimeException());
                return;
            }

            final boolean rolledBack = ops.rollbackTransaction();

            final Transaction result = ops.getTransaction().toBuilder()
                    .setStatus(rolledBack ? Transaction.Status.ROLLED_BACK : Transaction.Status.UNKNOWN)
                    .build();

            ops.setTransaction(result);

            log.debug("rollbackTransaction() -> {}", result.getStatus());

            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (final Exception e) {
            responseObserver.onError(Status.UNKNOWN
                    .augmentDescription(node)
                    .withDescription(e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        } finally {
            deleteDatabaseOperationByTransaction(transaction);
        }
    }

    @Override
    public void execute(
            final ExecuteConfig config,
            final StreamObserver<ExecuteResult> responseObserver) {
        try {
            final Transaction transaction = Transaction.newBuilder()
                    .setId(uniqueIdGenerator.generate(Transaction.class))
                    .setStatus(Transaction.Status.ACTIVE)
                    .setNode(node)
                    .build();

            MDC.put("transaction.id", DatabaseUtil.getMaskedId(transaction.getId()) + "@" + transaction.getNode());

            final DatabaseOperation ops = DatabaseOperation.builder()
                    .cryptoService(cryptoService)
                    .uniqueIdGenerator(uniqueIdGenerator)
                    .connectionString(config.getConnectionString())
                    .sqlFormatter(defaultSqlFormatter)
                    .transaction(transaction)
                    .build();

            ExecuteResult result;
            try {
                ops.openConnection();
                try {
                    ops.beginTransaction(BeginTransactionConfig.newBuilder()
                            .setTimeout(config.getTimeout())
                            .setReadOnly(false)
                            .build());
                    result = ops.execute(config);
                    ops.commitTransaction();
                } catch (final Exception e) {
                    log.debug(e.getMessage(), e);
                    ops.rollbackTransaction();
                    result = ExecuteResult.newBuilder()
                            .setRowsAffected(-1)
                            .build();
                }
            } finally {
                ops.closeConnection();
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
    public void query(
            final QueryConfig config,
            final StreamObserver<QueryResult> responseObserver) {
        try {
            final Transaction transaction = Transaction.newBuilder()
                    .setId(uniqueIdGenerator.generate(Transaction.class))
                    .setStatus(Transaction.Status.ACTIVE)
                    .setNode(node)
                    .build();

            MDC.put("transaction.id", DatabaseUtil.getMaskedId(transaction.getId()) + "@" + transaction.getNode());

            final DatabaseOperation ops = DatabaseOperation.builder()
                    .cryptoService(cryptoService)
                    .uniqueIdGenerator(uniqueIdGenerator)
                    .connectionString(config.getConnectionString())
                    .sqlFormatter(defaultSqlFormatter)
                    .transaction(transaction)
                    .build();

            QueryResult result;
            try {
                ops.openConnection();
                try {
                    ops.beginTransaction(BeginTransactionConfig.newBuilder()
                            .setTimeout(config.getTimeout())
                            .setReadOnly(true)
                            .build());
                    result = ops.query(config);
                    result = ops.next(NextConfig.newBuilder()
                            .setTransaction(transaction)
                            .setQueryResultId(result.getId())
                            .build());
                } finally {
                    ops.rollbackTransaction();
                }
            } finally {
                ops.closeConnection();
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
    public void executeTx(
            final ExecuteTxConfig config,
            final StreamObserver<ExecuteResult> responseObserver) {
        try {
            MDC.put("transaction.id", DatabaseUtil.getMaskedId(config.getTransaction().getId()) + "@" + config.getTransaction().getNode());

            if (shouldNotExecuteOnThisNode(config.getTransaction())) {
                dbpxyClient.invoke(config.getTransaction().getNode(), blockingStub -> {
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
        try {
            MDC.put("transaction.id", DatabaseUtil.getMaskedId(config.getTransaction().getId()) + "@" + config.getTransaction().getNode());

            if (shouldNotExecuteOnThisNode(config.getTransaction())) {
                dbpxyClient.invoke(config.getTransaction().getNode(), blockingStub -> {
                    final QueryResult result = blockingStub.queryTx(config);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                });
                return;
            }

            final DatabaseOperation ops = getDatabaseOperationByTransaction(config.getTransaction())
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
            QueryResult result = ops.query(config.getQueryConfig());
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
        }
    }

    @Override
    public void next(
            final NextConfig config,
            final StreamObserver<QueryResult> responseObserver) {
        try {
            MDC.put("transaction.id", DatabaseUtil.getMaskedId(config.getTransaction().getId()) + "@" + config.getTransaction().getNode());

            if (shouldNotExecuteOnThisNode(config.getTransaction())) {
                dbpxyClient.invoke(config.getTransaction().getNode(), blockingStub -> {
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
        try {
            MDC.put("transaction.id", DatabaseUtil.getMaskedId(config.getTransaction().getId()) + "@" + config.getTransaction().getNode());

            if (shouldNotExecuteOnThisNode(config.getTransaction())) {
                dbpxyClient.invoke(config.getTransaction().getNode(), blockingStub -> {
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
            log.debug("oh no! incorrect node {}", node);
        }
        return !matches;
    }

    private Optional<DatabaseOperation> getDatabaseOperationByTransaction(final Transaction transaction) {
        return Optional.ofNullable(transaction)
                .map(it -> cryptoService.decrypt(it.getId()))
                .map(transactionMap::get);
    }

    private void deleteDatabaseOperationByTransaction(final Transaction transaction) {
        Optional.ofNullable(transaction)
                .filter(it -> Objects.equals(it.getNode(), node))
                .map(it -> cryptoService.decrypt(it.getId()))
                .map(transactionMap::remove)
                .ifPresent(DatabaseOperation::closeConnection);
    }
}
