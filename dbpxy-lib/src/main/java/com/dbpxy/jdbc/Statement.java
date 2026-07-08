package com.dbpxy.jdbc;

/*-
 * #%L
 * dbpxy-lib
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

import com.dbpxy.exception.PreemptiveTimeoutException;
import com.dbpxy.exception.UnsupportedInReadOnlyModeException;
import com.dbpxy.exception.UnsupportedInWriteOnlyModeException;
import com.dbpxy.proto.*;
import com.dbpxy.util.DatabaseUtils;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class Statement implements java.sql.Statement {
    private static final String MDC_QUERY_ID = "dbpxy.qry.id";
    private static final Pattern SELECT_QUERY_PATTERN = Pattern.compile("(?i)^\\s*(select)\\s+.+");

    private final Connection connection;
    private final long defaultQueryTimeoutInMs;
    private Integer queryTimeout;
    private ResultSet resultSet;
    private boolean closed = false;

    @Override
    public ResultSet executeQuery(final String sql) throws SQLException {
        return executeQuery(sql, List.of());
    }

    protected ResultSet executeQuery(
            final String sql,
            final List<Value> params
    ) throws SQLException {
        try {
            final Transaction transaction = connection.getOrCreateTransaction(true);
            if (OffsetDateTime.now().isAfter(OffsetDateTime.parse(transaction.getExpiration()))) {
                throw new PreemptiveTimeoutException();
            }
            final QueryResult result = connection.getBlockingStub()
                    .withDeadlineAfter(connection.getDbpxyProperties().getReadTimeoutS(), TimeUnit.SECONDS)
                    .queryTx(QueryTxConfig.newBuilder()
                            .setTransaction(transaction)
                            .setQueryConfig(QueryConfig.newBuilder()
                                    .setQuery(sql)
                                    .setTimeoutInMs(getQueryTimeoutInMs())
                                    .addAllArgs(params)
                                    .build())
                            .build());
            this.resultSet = new ResultSet(
                    connection,
                    this,
                    result
            );
            MDC.put(MDC_QUERY_ID, DatabaseUtils.getMaskedId(result.getId()));
            log.debug("query executed");
            return resultSet;
        } catch (final StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.PERMISSION_DENIED
                    && Objects.equals(e.getStatus().getDescription(), "WRITE_ONLY_MODE")) {
                throw new UnsupportedInWriteOnlyModeException();
            }
            throw new SQLException(e);
        } catch (final RuntimeException e) {
            throw new SQLException(e);
        }
    }

    @Override
    public int executeUpdate(final String sql) throws SQLException {
        return executeUpdate(sql, List.of());
    }

    protected int executeUpdate(
            final String sql,
            final List<Value> params
    ) throws SQLException {
        try {
            final Transaction transaction = connection.getOrCreateTransaction(true);
            if (OffsetDateTime.now().isAfter(OffsetDateTime.parse(transaction.getExpiration()))) {
                throw new PreemptiveTimeoutException();
            }
            final ExecuteResult result = connection.getBlockingStub()
                    .withDeadlineAfter(connection.getDbpxyProperties().getWriteTimeoutS(), TimeUnit.SECONDS)
                    .executeTx(ExecuteTxConfig.newBuilder()
                            .setTransaction(transaction)
                            .setExecuteConfig(ExecuteConfig.newBuilder()
                                    .setQuery(sql)
                                    .setTimeoutInMs(getQueryTimeoutInMs())
                                    .addAllArgs(params)
                                    .build())
                            .build());
            return result.getRowsAffected();
        } catch (final StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.PERMISSION_DENIED
                    && Objects.equals(e.getStatus().getDescription(), "READ_ONLY_MODE")) {
                throw new UnsupportedInReadOnlyModeException();
            }
            throw new SQLException(e);
        } catch (final RuntimeException e) {
            throw new SQLException(e);
        }
    }

    @Override
    public void close() throws SQLException {
        try {
            final Transaction transaction = connection.getOrCreateTransaction(false);
            if (transaction == null) {
                log.debug("close statement skipped: no transaction");
                return;
            }
            connection.getBlockingStub()
                    .withDeadlineAfter(connection.getDbpxyProperties().getTimeoutS(), TimeUnit.SECONDS)
                    .closeStatement(Empty.getDefaultInstance());
        } catch (final RuntimeException e) {
            throw new SQLException(e);
        } finally {
            this.closed = true;
        }
    }

    @Override
    public int getMaxFieldSize() {
        return 0;
    }

    @Override
    public void setMaxFieldSize(int max) {
        // Do nothing
    }

    @Override
    public int getMaxRows() {
        return 0;
    }

    @Override
    public void setMaxRows(int max) {
        // Do nothing
    }

    @Override
    public void setEscapeProcessing(boolean enable) {
        // Do nothing
    }

    @Override
    public int getQueryTimeout() {
        if (queryTimeout != null) {
            return queryTimeout;
        }
        return (int) Duration.ofMillis(defaultQueryTimeoutInMs).toSeconds();
    }

    @Override
    public void setQueryTimeout(final int seconds) {
        this.queryTimeout = seconds;
    }

    protected long getQueryTimeoutInMs() {
        if (queryTimeout != null) {
            return Duration.ofSeconds(queryTimeout).toMillis();
        }
        return defaultQueryTimeoutInMs;
    }

    @Override
    public void cancel() throws SQLException {
        log.trace("public void cancel() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public SQLWarning getWarnings() {
        return null;
    }

    @Override
    public void clearWarnings() {
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        log.trace("public void setCursorName(String name) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean execute(final String sql) throws SQLException {
        if (SELECT_QUERY_PATTERN.matcher(sql).matches()) {
            executeQuery(sql);
            return true;
        } else {
            executeUpdate(sql);
            return false;
        }
    }

    @Override
    public ResultSet getResultSet() {
        return resultSet;
    }

    @Override
    public int getUpdateCount() {
        return -1;
    }

    @Override
    public boolean getMoreResults() {
        return false;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        resultSet.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() {
        return resultSet.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        resultSet.setFetchSize(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        return resultSet.getFetchSize();
    }

    @Override
    public int getResultSetConcurrency() {
        return resultSet.getConcurrency();
    }

    @Override
    public int getResultSetType() {
        return resultSet.getType();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        log.trace("public void addBatch(String sql) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void clearBatch() throws SQLException {
        log.trace("public void clearBatch() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        log.trace("public int[] executeBatch() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public boolean getMoreResults(int current) {
        return false;
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        log.trace("public ResultSet getGeneratedKeys() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        log.trace("public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        log.trace("public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        log.trace("public int executeUpdate(String sql, String[] columnNames) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        log.trace("public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        log.trace("public boolean execute(String sql, int[] columnIndexes) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        log.trace("public boolean execute(String sql, String[] columnNames) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int getResultSetHoldability() {
        return resultSet.getHoldability();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        log.trace("public void setPoolable(boolean poolable) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isPoolable() {
        return false;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        log.trace("public void closeOnCompletion() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isCloseOnCompletion() {
        return false;
    }

    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) {
        return iface.isInstance(this);
    }
}
