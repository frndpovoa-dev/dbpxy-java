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

import java.math.BigDecimal;
import java.sql.*;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.util.function.Predicate.not;

@Slf4j
@Builder
class DatabaseOperation {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ThreadLocal<SQLFormatter> SQL_FORMATTER = ThreadLocal.withInitial(() -> {
        final SQLFormatter formatter = new SQLFormatter();
        formatter.setClauseIndent("");
        formatter.setDoubleSpace(false);
        formatter.setLineLength(Integer.MAX_VALUE);
        formatter.setMultiLine(false);
        formatter.setNewline("");
        formatter.setWrapIndent("");
        return formatter;
    });

    @Getter
    private final AtomicBoolean shouldConnectionContinue = new AtomicBoolean(true);
    private final ConcurrentHashMap<String, LinkedBlockingQueue<DoWithResultSet>> queryTaskMap = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<DoWithConnection> taskQueue = new LinkedBlockingQueue<>() {
        @Override
        public boolean add(@NonNull final DoWithConnection doWithConnection) {
            if (!shouldConnectionContinue.get()) {
                return false;
            }
            return super.add(doWithConnection);
        }
    };
    private final CryptoService cryptoService;
    private final UniqueIdGenerator uniqueIdGenerator;
    @Getter
    private final long timeoutInMs;
    @Getter
    @Setter
    private Transaction transaction;

    boolean openConnection(final ConnectionString connectionString) {
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
                        .shouldConnectionContinue(shouldConnectionContinue)
                        .build();

                while (params.getShouldConnectionContinue().get()) {
                    final DoWithConnection callback = taskQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (callback != null) {
                        log.debug("before doWithConnection()");
                        callback.doWithConnection(params);
                        log.debug("after doWithConnection(), shouldContinue -> {}", params.getShouldConnectionContinue().get());
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
            params.getShouldConnectionContinue().set(false);
            future.complete(true);
        });
        if (accepted) {
            return future.join();
        } else {
            shouldConnectionContinue.set(false); // Shutdown now
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
                    final long waitInMs = 200;
                    long counterInMs = config.getTimeoutInMs();
                    while (params.getShouldConnectionContinue().get()) {
                        sleepUninterruptibly(Duration.ofMillis(waitInMs));
                        counterInMs -= waitInMs;
                        if (counterInMs <= 0) {
                            break;
                        }
                    }
                    if (params.getShouldConnectionContinue().get()) {
                        taskQueue.add(params1 -> {
                            try {
                                log.debug("timed out, trying to rollback...");
                                final boolean rolledBack = Optional.ofNullable(params1.getConnection())
                                        .flatMap(DatabaseOperation::rollback)
                                        .orElse(false);
                                log.debug("rolledBack -> {}", rolledBack);
                            } finally {
                                params1.getShouldConnectionContinue().set(false);
                            }
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
                        .flatMap(DatabaseOperation::commit)
                        .orElse(false);
                log.debug("committed -> {}", committed);
                future.complete(committed);
            } catch (final Exception e) {
                log.error(e.getMessage(), e);
                future.completeExceptionally(e);
            } finally {
                params.getShouldConnectionContinue().set(false);
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
                        .flatMap(DatabaseOperation::rollback)
                        .orElse(false);
                log.debug("rolledBack -> {}", rolledBack);
                future.complete(rolledBack);
            } catch (final Exception e) {
                log.error(e.getMessage(), e);
                future.completeExceptionally(e);
            } finally {
                params.getShouldConnectionContinue().set(false);
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
                stmt.setQueryTimeout(DatabaseUtil.sanitizeTimeout(config.getTimeoutInMs()));

                for (int i = 0; i < config.getArgsCount(); i++) {
                    setSqlArg(stmt, i + 1, config.getArgs(i));
                }

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
        final String queryResultId = uniqueIdGenerator.globalUUID(QueryResult.class.getName());
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final boolean accepted = taskQueue.add(params -> {
            MDC.put("query.id", DatabaseUtil.getMaskedId(queryResultId));
            log.debug("before prepared statement");
            try (final PreparedStatement stmt = params.getConnection().prepareStatement(config.getQuery())) {
                stmt.setQueryTimeout(DatabaseUtil.sanitizeTimeout(config.getTimeoutInMs()));
                stmt.setFetchSize(DatabaseUtil.sanitizeFetchSize(config.getFetchSize()));

                IntStream.range(0, config.getArgsCount())
                        .forEach(i -> setSqlArg(stmt, i + 1, config.getArgs(i)));

                logQuery(config.getQuery());

                final AtomicBoolean shouldResultSetContinue = new AtomicBoolean(true);
                final LinkedBlockingQueue<DoWithResultSet> taskQueueResultSet = new LinkedBlockingQueue<>() {
                    @Override
                    public boolean add(@NonNull final DoWithResultSet doWithResultSet) {
                        if (!params.getShouldConnectionContinue().get() || !shouldResultSetContinue.get()) {
                            return false;
                        }
                        return super.add(doWithResultSet);
                    }
                };

                log.debug("before open resultset");
                try (final ResultSet resultSet = stmt.executeQuery()) {
                    queryTaskMap.put(queryResultId, taskQueueResultSet);
                    future.complete(true);

                    final DoWithResultSet.Params resultSetParams = DoWithResultSet.Params.builder()
                            .resultSet(resultSet)
                            .taskExecutor(params.getTaskExecutor())
                            .shouldResultSetContinue(shouldResultSetContinue)
                            .build();
                    while (shouldResultSetContinue.get()) {
                        if (params.getShouldConnectionContinue().get()) {
                            final DoWithResultSet callback = taskQueueResultSet.poll(200, TimeUnit.MILLISECONDS);
                            if (callback != null) {
                                log.debug("before doWithResultSet()");
                                callback.doWithResultSet(resultSetParams);
                                log.debug("after doWithResultSet(), shouldContinue -> {}, shouldContinueResultSet -> {}", params.getShouldConnectionContinue().get(), shouldResultSetContinue.get());
                            }
                        } else {
                            shouldResultSetContinue.set(false);
                        }
                    }
                }
                log.debug("after close resultset");
            } catch (final Exception e) {
                log.error(e.getMessage(), e);
                future.completeExceptionally(e);
            } finally {
                log.debug("after prepared statement");
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
                        int rowsFetched = 0;
                        boolean next = true;
                        final List<Row> results = new ArrayList<>();
                        while (params.getShouldResultSetContinue().get() && next && rowsFetched < params.getResultSet().getFetchSize()) {
                            next = params.getResultSet().next();
                            if (next) {
                                rowsFetched++;
                                final Row.Builder rowBuilder = Row.newBuilder();
                                for (int i = 1; i <= params.getResultSet().getMetaData().getColumnCount(); i++) {
                                    rowBuilder.addCols(getSqlArg(params.getResultSet(), i));
                                }
                                results.add(rowBuilder.build());
                            }
                        }
                        if (!next) {
                            params.getShouldResultSetContinue().set(false);
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
                    params.getShouldResultSetContinue().set(false);
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
                .filter(DatabaseOperation::isActive)
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
                .filter(DatabaseOperation::isActive)
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

    private static boolean wasNull(final ResultSet rs) {
        try {
            return rs.wasNull();
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static int getScale(final ResultSet rs, final int i) {
        try {
            return rs.getMetaData().getScale(i);
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static int getPrecision(final ResultSet rs, final int i) {
        try {
            return rs.getMetaData().getPrecision(i);
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static int getColumnDisplaySize(final ResultSet rs, final int i) {
        try {
            return rs.getMetaData().getColumnDisplaySize(i);
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getColumnName(final ResultSet rs, final int i) {
        try {
            return rs.getMetaData().getColumnName(i);
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getColumnLabel(final ResultSet rs, final int i) {
        try {
            return rs.getMetaData().getColumnLabel(i);
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Value getSqlArg(final ResultSet rs, final int i) {
        try {
            switch (rs.getMetaData().getColumnType(i)) {
                case Types.BIGINT -> {
                    final long v = rs.getLong(i);
                    if (wasNull(rs)) return nullValue();
                    return Value.newBuilder()
                            .setCode(ValueCode.INT64)
                            .setData(ValueInt64.newBuilder()
                                    .setValue(v)
                                    .build()
                                    .toByteString()
                            )
                            .setSize(getColumnDisplaySize(rs, i))
                            .setName(getColumnName(rs, i))
                            .setLabel(getColumnLabel(rs, i))
                            .build();
                }
                case Types.BOOLEAN, Types.BIT -> {
                    final boolean v = rs.getBoolean(i);
                    if (wasNull(rs)) return nullValue();
                    return Value.newBuilder()
                            .setCode(ValueCode.BOOL)
                            .setData(ValueBool.newBuilder()
                                    .setValue(v)
                                    .build()
                                    .toByteString()
                            )
                            .setSize(getColumnDisplaySize(rs, i))
                            .setName(getColumnName(rs, i))
                            .setLabel(getColumnLabel(rs, i))
                            .build();
                }
                case Types.DATE -> {
                    final Date v = rs.getDate(i);
                    if (wasNull(rs)) return nullValue();
                    return Value.newBuilder()
                            .setCode(ValueCode.TIME)
                            .setData(ValueTime.newBuilder()
                                    .setValue(OffsetDateTime
                                            .ofInstant(Instant.ofEpochMilli(v.getTime()), ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                                    .build()
                                    .toByteString()
                            )
                            .setSize(getColumnDisplaySize(rs, i))
                            .setName(getColumnName(rs, i))
                            .setLabel(getColumnLabel(rs, i))
                            .build();
                }
                case Types.NUMERIC, Types.DOUBLE -> {
                    final BigDecimal v = rs.getBigDecimal(i);
                    if (wasNull(rs)) return nullValue();
                    return Value.newBuilder()
                            .setCode(ValueCode.FLOAT64)
                            .setData(ValueFloat64.newBuilder()
                                    .setValue(v.toString())
                                    .build()
                                    .toByteString()
                            )
                            .setSize(getColumnDisplaySize(rs, i))
                            .setName(getColumnName(rs, i))
                            .setLabel(getColumnLabel(rs, i))
                            .setScale(getScale(rs, i))
                            .setPrecision(getPrecision(rs, i))
                            .build();
                }
                case Types.SMALLINT, Types.INTEGER -> {
                    final int v = rs.getInt(i);
                    if (wasNull(rs)) return nullValue();
                    return Value.newBuilder()
                            .setCode(ValueCode.INT32)
                            .setData(ValueInt32.newBuilder()
                                    .setValue(v)
                                    .build()
                                    .toByteString()
                            )
                            .setSize(getColumnDisplaySize(rs, i))
                            .setName(getColumnName(rs, i))
                            .setLabel(getColumnLabel(rs, i))
                            .build();
                }
                case Types.TIMESTAMP -> {
                    final Timestamp v = rs.getTimestamp(i);
                    if (wasNull(rs)) return nullValue();
                    return Value.newBuilder()
                            .setCode(ValueCode.TIME)
                            .setData(ValueTime.newBuilder()
                                    .setValue(OffsetDateTime
                                            .ofInstant(v.toInstant(), ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                                    .build()
                                    .toByteString()
                            )
                            .setSize(getColumnDisplaySize(rs, i))
                            .setName(getColumnName(rs, i))
                            .setLabel(getColumnLabel(rs, i))
                            .build();
                }
                case Types.VARCHAR -> {
                    final String v = rs.getString(i);
                    if (wasNull(rs)) return nullValue();
                    return Value.newBuilder()
                            .setCode(ValueCode.STRING)
                            .setData(ValueString.newBuilder()
                                    .setValue(v)
                                    .build()
                                    .toByteString()
                            )
                            .setSize(getColumnDisplaySize(rs, i))
                            .setName(getColumnName(rs, i))
                            .setLabel(getColumnLabel(rs, i))
                            .build();
                }
                case Types.NULL -> {
                    return nullValue();
                }
                default -> {
                    throw new UnsupportedOperationException("Unsupported column type: " + rs.getMetaData().getColumnType(i));
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
                case FLOAT64 -> stmt.setBigDecimal(i, new BigDecimal(ValueFloat64.parseFrom(value.getData()).getValue()));
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

    private static void logQuery(final String query) {
        if (log.isDebugEnabled()) {
            log.debug("{}", Objects.toString(SQL_FORMATTER.get().prettyPrint(query)).trim());
        }
    }
}
