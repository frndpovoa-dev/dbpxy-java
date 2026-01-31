package com.dbpxy.service;

/*-
 * #%L
 * dbpxy
 * %%
 * Copyright (C) 2025 - 2026 Fernando Lemes Povoa
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


import com.dbpxy.jdbc.Array;
import com.dbpxy.proto.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.openjpa.lib.jdbc.SQLFormatter;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.util.function.Predicate.not;

@Slf4j
@Builder
class DatabaseOperation {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    @Getter
    private final AtomicBoolean shouldContinue = new AtomicBoolean(true);
    private final ConcurrentHashMap<String, LinkedBlockingQueue<DoWithResultSet>> queryTaskMap = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<DoWithConnection> taskQueue = new LinkedBlockingQueue<>() {
        @Override
        public boolean add(@NonNull final DoWithConnection doWithConnection) {
            if (!shouldContinue.get()) {
                return false;
            }
            return super.add(doWithConnection);
        }
    };
    private final CryptoService cryptoService;
    private final UniqueIdGenerator uniqueIdGenerator;
    private final ConnectionString connectionString;
    private final SQLFormatter sqlFormatter;
    @Getter
    private final long timeoutInMs;
    @Getter
    @Setter
    private Transaction transaction;

    boolean openConnection() {
        final Instant now = Instant.now();
        final ExecutorService taskExecutor = Executors.newCachedThreadPool(
                ThreadFactory.builder()
                        .prefix(Thread.currentThread().getName() + "-dbpxy-" + now.getEpochSecond() + now.getNano())
                        .build());
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            MDC.put("transaction.id", DatabaseUtil.getMaskedId(transaction.getId()) + "@" + transaction.getNode());
            final Properties props = new Properties();
            connectionString.getPropsList()
                    .forEach(prop -> props.put(prop.getName(), prop.getValue()));
            try (final Connection connection = DriverManager.getConnection(connectionString.getUrl(), props)) {
                final boolean opened = !connection.isClosed();
                log.debug("openConnection() -> {}", opened);
                future.complete(opened);

                final DoWithConnection.Params params = DoWithConnection.Params.builder()
                        .connection(connection)
                        .taskExecutor(taskExecutor)
                        .shouldContinue(shouldContinue)
                        .build();

                while (params.getShouldContinue().get()) {
                    final DoWithConnection callback = taskQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (callback != null) {
                        log.debug("before doWithConnection()");
                        callback.doWithConnection(params);
                        log.debug("after doWithConnection(), shouldContinue -> {}", params.getShouldContinue().get());
                    }
                }
            } catch (final Exception e) {
                log.error(e.getMessage(), e);
                future.completeExceptionally(e);
            } finally {
                taskExecutor.shutdownNow();
            }
        }, taskExecutor);
        return future.join();
    }

    boolean closeConnection() {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final boolean accepted = taskQueue.add(params -> {
            log.debug("closeConnection()");
            params.getShouldContinue().set(false);
            future.complete(true);
        });
        if (accepted) {
            return future.join();
        } else {
            shouldContinue.set(false); // Shutdown now
            return false;
        }
    }

    boolean beginTransaction(final BeginTransactionConfig config) {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final boolean accepted = taskQueue.add(params -> {
            try {
                params.getConnection().setAutoCommit(config.getAutoCommit());
                params.getConnection().setReadOnly(config.getReadOnly());

                CompletableFuture.runAsync(() -> {
                    final long wait = 200;
                    long timeout = config.getTimeout();
                    while (params.getShouldContinue().get()) {
                        sleepUninterruptibly(Duration.ofMillis(wait));
                        timeout -= wait;
                        if (timeout <= 0) {
                            break;
                        }
                    }
                    if (params.getShouldContinue().get()) {
                        taskQueue.add(params1 -> {
                            log.debug("timed out, trying to rollback...");
                            final boolean rolledBack = Optional.ofNullable(params1.getConnection())
                                    .filter(DatabaseOperation::isActive)
                                    .flatMap(DatabaseOperation::rollback)
                                    .orElse(false);
                            log.debug("rolledBack -> {}", rolledBack);
                            params1.getShouldContinue().set(false);
                        });
                    }
                }, params.getTaskExecutor());

                future.complete(true);
            } catch (final Exception e) {
                log.error(e.getMessage(), e);
                future.completeExceptionally(e);
            }
        });
        log.debug("begin transaction task accepted -> {}", accepted);
        if (accepted) {
            return future.join();
        } else {
            return false;
        }
    }

    boolean commitTransaction() {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final boolean accepted = taskQueue.add(params -> {
            try {
                final boolean committed = Optional.ofNullable(params.getConnection())
                        .filter(DatabaseOperation::isActive)
                        .flatMap(DatabaseOperation::commit)
                        .orElse(false);
                log.debug("committed -> {}", committed);
                params.getShouldContinue().set(false);
                future.complete(committed);
            } catch (final Exception e) {
                log.error(e.getMessage(), e);
                future.completeExceptionally(e);
            }
        });
        log.debug("commit task accepted -> {}", accepted);
        if (accepted) {
            return future.join();
        } else {
            return false;
        }
    }

    boolean rollbackTransaction() {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final boolean accepted = taskQueue.add(params -> {
            try {
                final boolean rolledBack = Optional.ofNullable(params.getConnection())
                        .filter(DatabaseOperation::isActive)
                        .flatMap(DatabaseOperation::rollback)
                        .orElse(false);
                log.debug("rolledBack -> {}", rolledBack);
                params.getShouldContinue().set(false);
                future.complete(rolledBack);
            } catch (final Exception e) {
                log.error(e.getMessage(), e);
                future.completeExceptionally(e);
            }
        });
        log.debug("rollback task accepted -> {}", accepted);
        if (accepted) {
            return future.join();
        } else {
            return false;
        }
    }

    public ExecuteResult execute(final ExecuteConfig config) {
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        final boolean accepted = taskQueue.add(params -> {
            try (final PreparedStatement stmt = params.getConnection().prepareStatement(config.getQuery())) {
                stmt.setQueryTimeout(getQueryTimeout(config.getTimeout()));

                IntStream.range(0, config.getArgsCount())
                        .forEach(i -> setSqlArg(stmt, i + 1, config.getArgs(i)));

                logQuery(config.getQuery());

                future.complete(stmt.executeUpdate());
            } catch (final Exception e) {
                log.error(e.getMessage(), e);
                future.completeExceptionally(e);
            }
        });
        log.debug("execute task accepted -> {}", accepted);
        if (accepted) {
            return ExecuteResult.newBuilder()
                    .setRowsAffected(future.join())
                    .build();
        } else {
            return ExecuteResult.newBuilder()
                    .setRowsAffected(-1)
                    .build();
        }
    }

    public QueryResult query(final QueryConfig config) {
        final String queryResultId = uniqueIdGenerator.generate(QueryResult.class);
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final boolean accepted = taskQueue.add(params -> {
            MDC.put("query.id", DatabaseUtil.getMaskedId(queryResultId));
            log.debug("before prepare statement");
            try (final PreparedStatement stmt = params.getConnection().prepareStatement(config.getQuery())) {
                stmt.setFetchSize(getFetchSize(config.getFetchSize()));

                IntStream.range(0, config.getArgsCount())
                        .forEach(i -> setSqlArg(stmt, i + 1, config.getArgs(i)));

                logQuery(config.getQuery());

                final AtomicBoolean shouldContinueResultSet = new AtomicBoolean(true);
                final LinkedBlockingQueue<DoWithResultSet> taskQueueResultSet = new LinkedBlockingQueue<>() {
                    @Override
                    public boolean add(@NonNull final DoWithResultSet doWithResultSet) {
                        if (!params.getShouldContinue().get() || !shouldContinueResultSet.get()) {
                            return false;
                        }
                        return super.add(doWithResultSet);
                    }
                };

                log.debug("before open result set");
                try (final ResultSet rs = stmt.executeQuery()) {
                    queryTaskMap.put(queryResultId, taskQueueResultSet);
                    future.complete(true);

                    final DoWithResultSet.Params resultSetParams = DoWithResultSet.Params.builder()
                            .rs(rs)
                            .taskExecutor(params.getTaskExecutor())
                            .shouldContinue(shouldContinueResultSet)
                            .build();

                    while (params.getShouldContinue().get() && shouldContinueResultSet.get()) {
                        final DoWithResultSet callback = taskQueueResultSet.poll(200, TimeUnit.MILLISECONDS);
                        if (callback != null) {
                            log.debug("before doWithResultSet()");
                            callback.doWithResultSet(resultSetParams);
                            log.debug("after doWithResultSet(), shouldContinue -> {}, shouldContinueResultSet -> {}", params.getShouldContinue().get(), shouldContinueResultSet.get());
                        }
                    }
                }
                log.debug("after close result set");
            } catch (final Exception e) {
                log.error(e.getMessage(), e);
                future.completeExceptionally(e);
            } finally {
                log.debug("after prepare statement");
                MDC.remove("query.id");
                queryTaskMap.remove(queryResultId);
            }
        });
        log.debug("query task accepted -> {}", accepted);
        if (accepted) {
            future.join();
        }
        return QueryResult.newBuilder()
                .setId(queryResultId)
                .build();
    }

    public QueryResult next(final NextConfig config) {
        final CompletableFuture<List<Row>> future = new CompletableFuture<>();
        final boolean accepted = Optional.ofNullable(queryTaskMap.get(config.getQueryResultId()))
                .map(queryTaskQueue -> queryTaskQueue.add(params -> {
                    try {
                        boolean next = true;
                        int rowsFetched = 0;
                        final List<Row> results = new ArrayList<>();
                        while (params.getShouldContinue().get() && next && rowsFetched < params.getRs().getFetchSize()) {
                            next = params.getRs().next();
                            if (next) {
                                rowsFetched++;
                                results.add(Row.newBuilder()
                                        .addAllCols(IntStream.range(1, params.getRs().getMetaData().getColumnCount() + 1)
                                                .mapToObj(i -> getSqlArg(params.getRs(), i))
                                                .toList()
                                        )
                                        .build());
                            }
                        }
                        if (!next) {
                            params.getShouldContinue().set(false);
                        }
                        future.complete(results);
                    } catch (final Exception e) {
                        log.error(e.getMessage(), e);
                        future.completeExceptionally(e);
                    }
                }))
                .orElse(false);
        log.debug("next task accepted -> {}", accepted);
        if (accepted) {
            return QueryResult.newBuilder()
                    .setId(config.getQueryResultId())
                    .addAllRows(future.join())
                    .build();
        } else {
            return QueryResult.newBuilder()
                    .setId(config.getQueryResultId())
                    .build();
        }
    }

    public boolean closeResultSet(final NextConfig config) {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final boolean accepted = Optional.ofNullable(queryTaskMap.get(config.getQueryResultId()))
                .map(queryTaskQueue -> queryTaskQueue.add(params -> {
                    params.getShouldContinue().set(false);
                    future.complete(true);
                }))
                .orElse(false);
        log.debug("close result set task accepted -> {}", accepted);
        if (accepted) {
            return future.join();
        } else {
            return false;
        }
    }

    static boolean isActive(final Connection conn) {
        try {
            return conn != null && !conn.isClosed();
        } catch (final SQLException e) {
            log.error(e.getMessage(), e);
            return false;
        }
    }

    static boolean isReadOnly(final Connection conn) {
        try {
            return conn != null && conn.isReadOnly();
        } catch (final SQLException e) {
            log.error(e.getMessage(), e);
            return true;
        }
    }

    static boolean isAutoCommit(final Connection conn) {
        try {
            return conn != null && conn.getAutoCommit();
        } catch (final SQLException e) {
            log.error(e.getMessage(), e);
            return true;
        }
    }

    static Optional<Boolean> commit(final Connection conn) {
        return Optional.ofNullable(conn)
                .filter(not(DatabaseOperation::isAutoCommit))
                .filter(not(DatabaseOperation::isReadOnly))
                .map(it -> {
                    try {
                        it.commit();
                        return true;
                    } catch (final SQLException e) {
                        log.error(e.getMessage(), e);
                        return false;
                    }
                });
    }

    static Optional<Boolean> rollback(final Connection conn) {
        return Optional.ofNullable(conn)
                .filter(not(DatabaseOperation::isAutoCommit))
                .filter(not(DatabaseOperation::isReadOnly))
                .map(it -> {
                    try {
                        it.rollback();
                        return true;
                    } catch (final SQLException e) {
                        log.error(e.getMessage(), e);
                        return false;
                    }
                });
    }

    private static Value nullValue() {
        return Value.newBuilder()
                .setCode(ValueCode.NULL)
                .setData(ValueNull.newBuilder()
                        .build()
                        .toByteString()
                )
                .build();
    }

    private static Value getSqlArg(final ResultSet rs, final int i) {
        final Predicate<Object> wasNotNull = ignored -> {
            try {
                return !rs.wasNull();
            } catch (final SQLException e) {
                throw new RuntimeException(e);
            }
        };
        final IntSupplier getScale = () -> {
            try {
                return rs.getMetaData().getScale(i);
            } catch (final SQLException e) {
                throw new RuntimeException(e);
            }
        };
        final IntSupplier getPrecision = () -> {
            try {
                return rs.getMetaData().getPrecision(i);
            } catch (final SQLException e) {
                throw new RuntimeException(e);
            }
        };
        final IntSupplier getColumnDisplaySize = () -> {
            try {
                return rs.getMetaData().getColumnDisplaySize(i);
            } catch (final SQLException e) {
                throw new RuntimeException(e);
            }
        };
        final Supplier<String> getColumnName = () -> {
            try {
                return rs.getMetaData().getColumnName(i);
            } catch (final SQLException e) {
                throw new RuntimeException(e);
            }
        };
        final Supplier<String> getColumnLabel = () -> {
            try {
                return rs.getMetaData().getColumnLabel(i);
            } catch (final SQLException e) {
                throw new RuntimeException(e);
            }
        };
        try {
            switch (JDBCType.valueOf(rs.getMetaData().getColumnType(i))) {
                case BIGINT -> {
                    return Optional.of(rs.getLong(i))
                            .filter(wasNotNull)
                            .map(v -> Value.newBuilder()
                                    .setCode(ValueCode.INT64)
                                    .setData(ValueInt64.newBuilder()
                                            .setValue(v)
                                            .build()
                                            .toByteString()
                                    )
                                    .setSize(getColumnDisplaySize.getAsInt())
                                    .setName(getColumnName.get())
                                    .setLabel(getColumnLabel.get())
                                    .build())
                            .orElseGet(DatabaseOperation::nullValue);
                }
                case BOOLEAN, BIT -> {
                    return Optional.of(rs.getBoolean(i))
                            .filter(wasNotNull)
                            .map(v -> Value.newBuilder()
                                    .setCode(ValueCode.BOOL)
                                    .setData(ValueBool.newBuilder()
                                            .setValue(v)
                                            .build()
                                            .toByteString()
                                    )
                                    .setSize(getColumnDisplaySize.getAsInt())
                                    .setName(getColumnName.get())
                                    .setLabel(getColumnLabel.get())
                                    .build())
                            .orElseGet(DatabaseOperation::nullValue);
                }
                case DATE -> {
                    return Optional.ofNullable(rs.getDate(i))
                            .filter(wasNotNull)
                            .map(v -> Value.newBuilder()
                                    .setCode(ValueCode.TIME)
                                    .setData(ValueTime.newBuilder()
                                            .setValue(OffsetDateTime
                                                    .ofInstant(v.toInstant(), ZoneId.systemDefault())
                                                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                                            .build()
                                            .toByteString()
                                    )
                                    .setSize(getColumnDisplaySize.getAsInt())
                                    .setName(getColumnName.get())
                                    .setLabel(getColumnLabel.get())
                                    .build())
                            .orElseGet(DatabaseOperation::nullValue);
                }
                case NUMERIC, DOUBLE -> {
                    return Optional.of(rs.getDouble(i))
                            .filter(wasNotNull)
                            .map(v -> Value.newBuilder()
                                    .setCode(ValueCode.FLOAT64)
                                    .setData(ValueFloat64.newBuilder()
                                            .setValue(v)
                                            .build()
                                            .toByteString()
                                    )
                                    .setSize(getColumnDisplaySize.getAsInt())
                                    .setName(getColumnName.get())
                                    .setLabel(getColumnLabel.get())
                                    .setScale(getScale.getAsInt())
                                    .setPrecision(getPrecision.getAsInt())
                                    .build())
                            .orElseGet(DatabaseOperation::nullValue);
                }
                case SMALLINT, INTEGER -> {
                    return Optional.of(rs.getInt(i))
                            .filter(wasNotNull)
                            .map(v -> Value.newBuilder()
                                    .setCode(ValueCode.INT32)
                                    .setData(ValueInt32.newBuilder()
                                            .setValue(v)
                                            .build()
                                            .toByteString()
                                    )
                                    .setSize(getColumnDisplaySize.getAsInt())
                                    .setName(getColumnName.get())
                                    .setLabel(getColumnLabel.get())
                                    .build())
                            .orElseGet(DatabaseOperation::nullValue);
                }
                case TIMESTAMP -> {
                    return Optional.ofNullable(rs.getTimestamp(i))
                            .filter(wasNotNull)
                            .map(v -> Value.newBuilder()
                                    .setCode(ValueCode.TIME)
                                    .setData(ValueTime.newBuilder()
                                            .setValue(OffsetDateTime
                                                    .ofInstant(v.toInstant(), ZoneId.systemDefault())
                                                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                                            .build()
                                            .toByteString()
                                    )
                                    .setSize(getColumnDisplaySize.getAsInt())
                                    .setName(getColumnName.get())
                                    .setLabel(getColumnLabel.get())
                                    .build())
                            .orElseGet(DatabaseOperation::nullValue);
                }
                case VARCHAR -> {
                    return Optional.ofNullable(rs.getString(i))
                            .filter(wasNotNull)
                            .map(v -> Value.newBuilder()
                                    .setCode(ValueCode.STRING)
                                    .setData(ValueString.newBuilder()
                                            .setValue(v)
                                            .build()
                                            .toByteString()
                                    )
                                    .setSize(getColumnDisplaySize.getAsInt())
                                    .setName(getColumnName.get())
                                    .setLabel(getColumnLabel.get())
                                    .build())
                            .orElseGet(DatabaseOperation::nullValue);
                }
                case NULL -> {
                    return nullValue();
                }
                default -> {
                    return null;
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setSqlArg(final PreparedStatement stmt, final int i, final Value value) {
        try {
            switch (value.getCode()) {
                case INT64 -> stmt.setLong(i, ValueInt64.parseFrom(value.getData()).getValue());
                case FLOAT64 -> stmt.setDouble(i, ValueFloat64.parseFrom(value.getData()).getValue());
                case BOOL -> stmt.setBoolean(i, ValueBool.parseFrom(value.getData()).getValue());
                case STRING -> stmt.setString(i, ValueString.parseFrom(value.getData()).getValue());
                case TIME -> {
                    final String s = ValueTime.parseFrom(value.getData()).getValue();
                    final OffsetDateTime odt = OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    stmt.setTimestamp(i, new Timestamp(odt.toInstant().toEpochMilli()));
                }
                case NULL -> stmt.setNull(i, Types.NULL);
                case ARRAY -> {
                    final ArrayMirror mirror = OBJECT_MAPPER.readValue(
                            ValueString.parseFrom(value.getData()).getValue(),
                            new TypeReference<>() {
                            });
                    stmt.setArray(i, new Array(
                            mirror.getBaseTypeName(),
                            mirror.getBaseType(),
                            mirror.getArray().toArray()));
                }
                default -> stmt.setNull(i, Types.NULL);
            }
        } catch (final InvalidProtocolBufferException
                       | SQLException
                       | JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static int getFetchSize(final long fetchSize) {
        return Math.clamp(fetchSize, 25, Integer.MAX_VALUE);
    }

    private static int getQueryTimeout(final long timeout) {
        return (int) Math.min(timeout, Integer.MAX_VALUE);
    }

    private void logQuery(final String query) {
        log.debug("{}", Objects.toString(sqlFormatter.prettyPrint(query)).trim());
    }
}
