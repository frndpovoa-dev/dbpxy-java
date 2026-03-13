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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static java.util.function.Predicate.not;

@Slf4j
@Builder
class DatabaseOperation {
    private static final String MDC_TRANSACTION_ID = "transaction.id";
    private static final String MDC_QUERY_ID = "query.id";

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

    private final CryptoService cryptoService;
    private final ExecutorService taskExecutor;
    private final UniqueIdGenerator uniqueIdGenerator;
    @Getter
    private final long timeoutInMs;
    @Getter
    @Setter
    private Transaction transaction;
    private LinkedBlockingQueue<DoWithConnection> taskQueue;
    @Builder.Default
    private final ConcurrentHashMap<String, LinkedBlockingQueue<DoWithResultSet>> queryTaskMap = new ConcurrentHashMap<>();

    boolean openConnection(final ConnectionString connectionString) {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {

            MDC.put(MDC_TRANSACTION_ID, DatabaseUtil.getMaskedId(transaction.getId()) + "@" + transaction.getNode());

            final Properties props = new Properties();
            connectionString.getPropsList()
                    .forEach(prop -> props.put(prop.getName(), prop.getValue()));

            try (final Connection connection = DriverManager.getConnection(connectionString.getUrl(), props);
                 final ScheduledExecutorService rollbackExecutor = Executors.newSingleThreadScheduledExecutor(ThreadFactory.builder().prefix(Thread.currentThread().getName() + "-rollback-").build())) {

                final DoWithConnection.Params params = DoWithConnection.Params.builder()
                        .connection(connection)
                        .rollbackExecutor(rollbackExecutor)
                        .build();

                this.taskQueue = new LinkedBlockingQueue<>() {
                    @Override
                    public boolean add(@NonNull final DoWithConnection doWithConnection) {
                        if (!params.shouldConnectionContinue()) {
                            return false;
                        }
                        return super.add(doWithConnection);
                    }
                };

                final boolean opened = !connection.isClosed();
                log.debug("openConnection() -> {}", opened);
                future.complete(opened);

                while (params.shouldConnectionContinue()) {
                    final DoWithConnection callback = taskQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (callback != null) {
                        log.debug("before doWithConnection()");
                        callback.doWithConnection(params);
                        log.debug("after doWithConnection(), shouldConnectionContinue: {}", params.shouldConnectionContinue());
                    }
                }

            } catch (final Exception e) {
                log.error(e.getMessage(), e);
                future.completeExceptionally(e);
            } finally {
                MDC.remove(MDC_TRANSACTION_ID);
            }
        }, taskExecutor);
        return future.join();
    }

    boolean closeConnection() {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();

        final boolean accepted = taskQueue.add(params -> {
            log.debug("closeConnection()");
            params.stopConnection();
            future.complete(true);
        });

        if (accepted) {
            return future.join();
        } else {
            return false;
        }
    }

    boolean beginTransaction(final BeginTransactionConfig config) {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();

        final boolean accepted = taskQueue.add(params -> {
            try {
                log.debug("beginTransaction() -> autoCommit: {}, readOnly: {}, transactionTimeout: {}",
                        config.getAutoCommit(),
                        config.getReadOnly(),
                        config.getTimeoutInMs());

                params.getConnection().setAutoCommit(config.getAutoCommit());
                params.getConnection().setReadOnly(config.getReadOnly());

                params.setRollbackTask(config.getTimeoutInMs(), () -> {
                    if (params.shouldConnectionContinue()) {
                        taskQueue.add(rollbackParams -> {
                            try {
                                log.debug("timed out, trying to rollback...");
                                final boolean rolledBack = Optional.ofNullable(rollbackParams.getConnection())
                                        .flatMap(DatabaseOperation::rollback)
                                        .orElse(false);
                                log.debug("rolledBack -> {}", rolledBack);
                            } finally {
                                rollbackParams.stopConnection();
                            }
                        });
                    }
                });

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
                params.stopConnection();
                params.cancelRollbackTask();
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
                params.stopConnection();
                params.cancelRollbackTask();
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
                log.debug("execute() -> executeTimeout: {}",
                        config.getTimeoutInMs());

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
            MDC.put(MDC_QUERY_ID, DatabaseUtil.getMaskedId(queryResultId));
            log.debug("before prepared statement");
            try (final PreparedStatement stmt = params.getConnection().prepareStatement(config.getQuery())) {
                log.debug("query() -> fetchSize: {}, queryTimeout: {}",
                        config.getFetchSize(),
                        config.getTimeoutInMs());

                stmt.setQueryTimeout(DatabaseUtil.sanitizeTimeout(config.getTimeoutInMs()));
                stmt.setFetchSize(DatabaseUtil.sanitizeFetchSize(config.getFetchSize()));

                IntStream.range(0, config.getArgsCount())
                        .forEach(i -> setSqlArg(stmt, i + 1, config.getArgs(i)));

                logQuery(config.getQuery());

                log.debug("before open resultset");
                try (final ResultSet resultSet = stmt.executeQuery()) {

                    final DoWithResultSet.Params resultSetParams = DoWithResultSet.Params.builder()
                            .resultSet(resultSet)
                            .build();

                    final LinkedBlockingQueue<DoWithResultSet> taskQueueResultSet = new LinkedBlockingQueue<>() {
                        @Override
                        public boolean add(@NonNull final DoWithResultSet doWithResultSet) {
                            if (!params.shouldConnectionContinue() || !resultSetParams.shouldResultSetContinue()) {
                                return false;
                            }
                            return super.add(doWithResultSet);
                        }
                    };

                    queryTaskMap.put(queryResultId, taskQueueResultSet);
                    future.complete(true);

                    while (resultSetParams.shouldResultSetContinue()) {
                        if (params.shouldConnectionContinue()) {
                            final DoWithResultSet callback = taskQueueResultSet.poll(200, TimeUnit.MILLISECONDS);
                            if (callback != null) {
                                log.debug("before doWithResultSet()");
                                callback.doWithResultSet(resultSetParams);
                                log.debug("after doWithResultSet(), shouldConnectionContinue: {}, shouldResultSetContinue: {}", params.shouldConnectionContinue(), resultSetParams.shouldResultSetContinue());
                            }
                        } else {
                            resultSetParams.stopResultSet();
                        }
                    }
                }

                log.debug("after close resultset");
            } catch (final Exception e) {
                log.error(e.getMessage(), e);
                future.completeExceptionally(e);
            } finally {
                MDC.remove(MDC_QUERY_ID);
                queryTaskMap.remove(queryResultId);
                log.debug("after prepared statement");
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
                .map(queryTaskQueue -> queryTaskQueue.add(resultSetParams -> {
                    try {
                        final List<Row> results = new ArrayList<>();

                        final ResultSet rs = resultSetParams.getResultSet();

                        boolean next = true;
                        int rowsFetched = 0;
                        while (resultSetParams.shouldResultSetContinue() && next && rowsFetched < rs.getFetchSize()) {
                            next = rs.next();
                            if (next) {
                                rowsFetched++;
                                final Row.Builder rowBuilder = Row.newBuilder();
                                final ResultSetMetaData metadata = rs.getMetaData();
                                final int columnCount = metadata.getColumnCount();
                                for (int i = 1; i <= columnCount; i++) {
                                    rowBuilder.addCols(getSqlArg(metadata, rs, i));
                                }
                                results.add(rowBuilder.build());
                            }
                        }

                        if (!next) {
                            resultSetParams.stopResultSet();
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
                .map(queryTaskQueue -> queryTaskQueue.add(resultSetParams -> {
                    resultSetParams.stopResultSet();
                    future.complete(true);
                }))
                .orElse(false);

        log.debug("close resultset task accepted -> {}", accepted);
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
                .setData(ValueNull.newBuilder().build().toByteString())
                .build();
    }

    private static Value getSqlArg(
            final ResultSetMetaData metadata,
            final ResultSet rs,
            final int i) {
        try {
            switch (metadata.getColumnType(i)) {
                case Types.BIGINT -> {
                    return int64Value(metadata, rs, i);
                }
                case Types.BOOLEAN, Types.BIT -> {
                    return booleanValue(metadata, rs, i);
                }
                case Types.DATE -> {
                    return timeValue(metadata, rs, i);
                }
                case Types.NUMERIC, Types.DOUBLE -> {
                    return float64Value(metadata, rs, i);
                }
                case Types.SMALLINT, Types.INTEGER -> {
                    return int32Value(metadata, rs, i);
                }
                case Types.TIMESTAMP -> {
                    return timestampValue(metadata, rs, i);
                }
                case Types.VARCHAR -> {
                    return varcharValue(metadata, rs, i);
                }
                case Types.NULL -> {
                    return nullValue();
                }
                default -> {
                    throw new UnsupportedOperationException("Unsupported column type: " + metadata.getColumnType(i));
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Value.Builder createValueBuilder(
            final ResultSetMetaData metadata,
            final int i) throws SQLException {
        return Value.newBuilder()
                .setSize(metadata.getColumnDisplaySize(i))
                .setName(metadata.getColumnName(i))
                .setLabel(metadata.getColumnLabel(i));
    }

    private static @NonNull Value int64Value(
            final ResultSetMetaData metadata,
            final ResultSet rs,
            final int i) throws SQLException {
        final long v = rs.getLong(i);
        if (rs.wasNull()) return nullValue();
        return createValueBuilder(metadata, i)
                .setCode(ValueCode.INT64)
                .setData(ValueInt64.newBuilder().setValue(v).build().toByteString())
                .build();
    }

    private static @NonNull Value booleanValue(
            final ResultSetMetaData metadata,
            final ResultSet rs,
            final int i) throws SQLException {
        final boolean v = rs.getBoolean(i);
        if (rs.wasNull()) return nullValue();
        return createValueBuilder(metadata, i)
                .setCode(ValueCode.BOOL)
                .setData(ValueBool.newBuilder().setValue(v).build().toByteString())
                .build();
    }

    private static @NonNull Value timeValue(
            final ResultSetMetaData metadata,
            final ResultSet rs,
            final int i) throws SQLException {
        final Date v = rs.getDate(i);
        if (rs.wasNull()) return nullValue();
        return createValueBuilder(metadata, i)
                .setCode(ValueCode.TIME)
                .setData(ValueTime.newBuilder()
                        .setValue(OffsetDateTime
                                .ofInstant(Instant.ofEpochMilli(v.getTime()), ZoneId.systemDefault())
                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        .build()
                        .toByteString()
                )
                .build();
    }

    private static @NonNull Value float64Value(
            final ResultSetMetaData metadata,
            final ResultSet rs,
            final int i) throws SQLException {
        final BigDecimal v = rs.getBigDecimal(i);
        if (rs.wasNull()) return nullValue();
        return createValueBuilder(metadata, i)
                .setCode(ValueCode.FLOAT64)
                .setData(ValueFloat64.newBuilder().setValue(v.toString()).build().toByteString())
                .setScale(metadata.getScale(i))
                .setPrecision(metadata.getPrecision(i))
                .build();
    }

    private static @NonNull Value int32Value(
            final ResultSetMetaData metadata,
            final ResultSet rs,
            final int i) throws SQLException {
        final int v = rs.getInt(i);
        if (rs.wasNull()) return nullValue();
        return createValueBuilder(metadata, i)
                .setCode(ValueCode.INT32)
                .setData(ValueInt32.newBuilder().setValue(v).build().toByteString())
                .build();
    }

    private static @NonNull Value timestampValue(
            final ResultSetMetaData metadata,
            final ResultSet rs,
            final int i) throws SQLException {
        final Timestamp v = rs.getTimestamp(i);
        if (rs.wasNull()) return nullValue();
        return createValueBuilder(metadata, i)
                .setCode(ValueCode.TIME)
                .setData(ValueTime.newBuilder()
                        .setValue(OffsetDateTime
                                .ofInstant(v.toInstant(), ZoneId.systemDefault())
                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        .build()
                        .toByteString()
                )
                .build();
    }

    private static @NonNull Value varcharValue(
            final ResultSetMetaData metadata,
            final ResultSet rs,
            final int i) throws SQLException {
        final String v = rs.getString(i);
        if (rs.wasNull()) return nullValue();
        return createValueBuilder(metadata, i)
                .setCode(ValueCode.STRING)
                .setData(ValueString.newBuilder().setValue(v).build().toByteString())
                .build();
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
            log.debug(Objects.toString(SQL_FORMATTER.get().prettyPrint(query)).trim());
        }
    }
}
