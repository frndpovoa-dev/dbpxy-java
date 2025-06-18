package dev.frndpovoa.project1.databaseproxy.jdbc;

/*-
 * #%L
 * database-proxy-lib
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

import dev.frndpovoa.project1.databaseproxy.ConnectionHolder;
import dev.frndpovoa.project1.databaseproxy.proto.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.Optional;

@Getter
@Setter
@RequiredArgsConstructor
public class Statement implements java.sql.Statement {
    private ResultSet resultSet;
    private Long timeout = 60_000L;

    protected void beginTransaction(
            final Connection connection
    ) throws SQLException {
        if (!connection.getAutoCommit() && connection.getTransaction() == null) {
            final Transaction transaction = connection.getBlockingStub()
                    .beginTransaction(BeginTransactionConfig.newBuilder()
                            .setConnectionString(connection.getDatabaseProxyDataSourceProperties().getUrl())
                            .setTimeout(timeout)
                            .setReadOnly(connection.isReadOnly())
                            .build());
            connection.pushTransaction(transaction);
        }
    }

    @Override
    public ResultSet executeQuery(final String sql) throws SQLException {
        final Connection connection = ConnectionHolder.getConnection();
        beginTransaction(connection);

        final QueryResult result = connection.getTransaction() == null ?
                connection.getBlockingStub().query(QueryConfig.newBuilder()
                        .setQuery(sql)
                        .setTimeout(Optional.ofNullable(timeout).orElse(60_000L))
                        .setConnectionString(connection.getDatabaseProxyDataSourceProperties().getUrl())
                        .build())
                : connection.getBlockingStub().queryTx(QueryTxConfig.newBuilder()
                .setTransaction(connection.getTransaction())
                .setQueryConfig(QueryConfig.newBuilder()
                        .setQuery(sql)
                        .setTimeout(Optional.ofNullable(timeout).orElse(60_000L))
                        .build())
                .build());
        this.resultSet = new ResultSet(
                connection,
                this,
                result
        );
        return resultSet;
    }

    @Override
    public int executeUpdate(final String sql) throws SQLException {
        final Connection connection = ConnectionHolder.getConnection();
        beginTransaction(connection);

        final ExecuteResult result = connection.getTransaction() == null ?
                connection.getBlockingStub().execute(ExecuteConfig.newBuilder()
                        .setQuery(sql)
                        .setTimeout(Optional.ofNullable(timeout).orElse(60_000L))
                        .setConnectionString(connection.getDatabaseProxyDataSourceProperties().getUrl())
                        .build())
                : connection.getBlockingStub().executeTx(ExecuteTxConfig.newBuilder()
                .setTransaction(connection.getTransaction())
                .setExecuteConfig(ExecuteConfig.newBuilder()
                        .setQuery(sql)
                        .setTimeout(Optional.ofNullable(timeout).orElse(60_000L))
                        .build())
                .build());
        return result.getRowsAffected();
    }

    @Override
    public void close() throws SQLException {
        final Connection connection = ConnectionHolder.getConnection();
        connection.getBlockingStub().closeStatement(Empty.getDefaultInstance());
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return 0;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        // Do nothing
    }

    @Override
    public int getMaxRows() throws SQLException {
        return 0;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        // Do nothing
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        // Do nothing
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return Optional.ofNullable(timeout).orElse(60_000L).intValue();
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        this.timeout = seconds * 1000L;
    }

    @Override
    public void cancel() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean execute(final String sql) throws SQLException {
        if (sql.matches("(?i)^\\s*(select)\\s+.+")) {
            executeQuery(sql);
            return true;
        } else {
            executeUpdate(sql);
            return false;
        }
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        return resultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return -1;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return false;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        resultSet.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() throws SQLException {
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
    public int getResultSetConcurrency() throws SQLException {
        return resultSet.getConcurrency();
    }

    @Override
    public int getResultSetType() throws SQLException {
        return resultSet.getType();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void clearBatch() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int[] executeBatch() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Connection getConnection() throws SQLException {
        return ConnectionHolder.getConnection();
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return false;
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return resultSet.getHoldability();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return false;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return false;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }
}
