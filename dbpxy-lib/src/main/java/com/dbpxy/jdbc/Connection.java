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

import com.dbpxy.ConnectionHolder;
import com.dbpxy.config.DbpxyDatasourceProperties;
import com.dbpxy.config.DbpxyProperties;
import com.dbpxy.postgresql.PgDatabaseMetaData;
import com.dbpxy.proto.*;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Connection implements java.sql.Connection {
    private static final List<Transaction.Status> ACTIVE_TRANSACTION_STATUSES = List.of(Transaction.Status.ACTIVE, Transaction.Status.JOINED);
    private static final Pattern TRANSACTION_ID_PATTERN = Pattern.compile("^(.+?)@(.+)$");
    private static final long DEFAULT_QUERY_TIMEOUT_IN_MS = Duration.ofMinutes(1).toMillis();
    @Getter
    @EqualsAndHashCode.Include
    private final String id = UUID.randomUUID().toString();
    private final ConnectionHolder connectionHolder;
    @Getter
    private final ConnectionString connectionString;
    private final ManagedChannel channel;
    @Getter
    private final DbpxyGrpc.DbpxyBlockingStub blockingStub;
    @Setter
    private long transactionTimeoutInMs = DEFAULT_QUERY_TIMEOUT_IN_MS;
    private boolean autoCommit = true;
    private boolean readOnly = false;
    private boolean closed = false;
    private String catalog;

    private final Deque<Transaction> transactions = new ArrayDeque<>();

    public String getTransactionId() {
        return Optional.ofNullable(getTransaction(true))
                .map(transaction -> transaction.getId() + "@" + transaction.getNode())
                .orElse(null);
    }

    public synchronized Transaction getTransaction(final boolean create) {
        if (create) {
            transactions.removeIf(transaction ->
                    !ACTIVE_TRANSACTION_STATUSES.contains(transaction.getStatus()));
            if (transactions.isEmpty()) {
                final Transaction transaction = blockingStub
                        .beginTransaction(BeginTransactionConfig.newBuilder()
                                .setConnectionString(connectionString)
                                .setTimeoutInMs(transactionTimeoutInMs)
                                .setAutoCommit(autoCommit)
                                .setReadOnly(readOnly)
                                .build());
                pushTransaction(transaction);
            }
        }
        return transactions.peek();
    }

    private void pushTransaction(final Transaction transaction) {
        transactions.push(transaction);
    }

    private void popTransaction(final Transaction transaction) {
        transactions.removeIf(it ->
                Objects.equals(it.getId(), transaction.getId()) && Objects.equals(it.getNode(), transaction.getNode()));
    }

    private void replaceTransaction(final Transaction out, final Transaction in) {
        popTransaction(out);
        pushTransaction(in);
    }

    public void doWithSharedTransaction(
            final String transactionId,
            final Runnable runnable) throws Exception {
        if (transactionId == null) {
            runnable.run();
            return;
        }
        try {
            joinSharedTransaction(transactionId);
            runnable.run();
        } finally {
            leaveSharedTransaction(transactionId);
        }
    }

    public <T> T doWithSharedTransaction(
            final String transactionId,
            final Callable<T> callable) throws Exception {
        if (transactionId == null) {
            return callable.call();
        }
        try {
            joinSharedTransaction(transactionId);
            return callable.call();
        } finally {
            leaveSharedTransaction(transactionId);
        }
    }

    public void joinSharedTransaction(final String transactionId) throws SQLException {
        log.debug("joinSharedTransaction({})", getMaskedId(transactionId));
        final Matcher m = TRANSACTION_ID_PATTERN.matcher(transactionId);
        if (!m.matches()) {
            throw new SQLException("Invalid transaction id format");
        }
        final Transaction transaction = Transaction.newBuilder()
                .setId(m.group(1))
                .setNode(m.group(2))
                .setStatus(Transaction.Status.JOINED)
                .build();
        pushTransaction(transaction);
        log.debug("joined(conn: {}, tx: {})", id, getMaskedId(transactionId));
    }

    public void leaveSharedTransaction(final String transactionId) throws SQLException {
        log.debug("leaveSharedTransaction({})", getMaskedId(transactionId));
        final Matcher m = TRANSACTION_ID_PATTERN.matcher(transactionId);
        if (!m.matches()) {
            throw new SQLException("Invalid transaction id format");
        }
        popTransaction(Transaction.newBuilder()
                .setId(m.group(1))
                .setNode(m.group(2))
                .build());
        log.debug("left(conn: {}, tx: {})", id, getMaskedId(transactionId));
    }

    public Connection(
            final ConnectionHolder connectionHolder,
            final DbpxyProperties dbpxyProperties,
            final DbpxyDatasourceProperties dbpxyDatasourceProperties,
            final String dbpxyCertPath
    ) throws SQLException {
        try {
            try (final InputStream cert = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(dbpxyCertPath))) {
                final ChannelCredentials credentials = TlsChannelCredentials.newBuilder()
                        .trustManager(cert)
                        .build();
                this.channel = Grpc.newChannelBuilderForAddress(
                                dbpxyProperties.getHostname(),
                                dbpxyProperties.getPort(),
                                credentials)
                        .build();
            }
            this.blockingStub = DbpxyGrpc.newBlockingStub(channel);
            this.connectionHolder = connectionHolder;
            this.connectionString = ConnectionString.newBuilder()
                    .setUrl(dbpxyDatasourceProperties.getUrl())
                    .addAllProps(dbpxyDatasourceProperties.getProps().entrySet().stream()
                            .map(e -> ConnectionStringProp.newBuilder()
                                    .setName(e.getKey())
                                    .setValue(e.getValue())
                                    .build())
                            .toList())
                    .build();
            connectionHolder.pushConnection(this);
            log.debug("open(conn: {})", id);
        } catch (final IOException e) {
            throw new SQLException(e);
        }
    }

    @Override
    public Statement createStatement() {
        return new Statement(this, DEFAULT_QUERY_TIMEOUT_IN_MS);
    }

    @Override
    public PreparedStatement prepareStatement(final String sql) {
        return new PreparedStatement(this, DEFAULT_QUERY_TIMEOUT_IN_MS, sql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        log.trace("public CallableStatement prepareCall(String sql) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public String nativeSQL(final String sql) {
        return sql;
    }

    @Override
    public void setAutoCommit(final boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean getAutoCommit() {
        return autoCommit;
    }

    private static String getMaskedId(final String id) {
        return id.substring(0, Math.min(32, id.length()));
    }

    @Override
    public void commit() throws SQLException {
        final Transaction transaction = getTransaction(false);
        if (readOnly || transaction == null) {
            log.debug("commit skipped (conn: {})", id);
        } else if (transaction.getStatus() == Transaction.Status.ACTIVE) {
            log.debug("commit(conn: {}, tx: {})", id, getMaskedId(transaction.getId()));
            try {
                final Transaction newTransaction = blockingStub.commitTransaction(transaction);
                replaceTransaction(transaction, newTransaction);
            } catch (final RuntimeException e) {
                throw new SQLException(e.getMessage());
            }
        }
    }

    @Override
    public void rollback() throws SQLException {
        final Transaction transaction = getTransaction(false);
        if (readOnly || transaction == null) {
            log.debug("rollback skipped (conn: {})", id);
        } else if (transaction.getStatus() == Transaction.Status.ACTIVE) {
            log.debug("rollback(conn: {}, tx: {})", id, getMaskedId(transaction.getId()));
            try {
                final Transaction newTransaction = blockingStub.rollbackTransaction(transaction);
                replaceTransaction(transaction, newTransaction);
            } catch (final RuntimeException e) {
                throw new SQLException(e.getMessage());
            }
        }
    }

    @Override
    public void close() throws SQLException {
        final Transaction transaction = getTransaction(false);
        if (transaction != null && transaction.getStatus() == Transaction.Status.JOINED) {
            log.debug("close skipped (conn: {})", id);
        } else {
            try {
                if (autoCommit) {
                    commit();
                }
                log.debug("close(conn: {})", id);
                channel.shutdownNow();
            } finally {
                this.closed = true;
                connectionHolder.popConnection(this);
            }
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public PgDatabaseMetaData getMetaData() {
        return new PgDatabaseMetaData(this);
    }

    @Override
    public void setReadOnly(final boolean readOnly) {
        this.readOnly = readOnly;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public void setCatalog(final String catalog) {
        this.catalog = catalog;
    }

    @Override
    public String getCatalog() {
        return catalog;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        log.trace("public void setTransactionIsolation(int level) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int getTransactionIsolation() {
        return java.sql.Connection.TRANSACTION_READ_COMMITTED;
    }

    @Override
    public SQLWarning getWarnings() {
        return null;
    }

    @Override
    public void clearWarnings() {
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        log.trace("public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(
            final String sql,
            final int resultSetType,
            final int resultSetConcurrency) {
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        log.trace("public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        log.trace("public Map<String, Class<?>> getTypeMap() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        log.trace("public void setTypeMap(Map<String, Class<?>> map) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        log.trace("public void setHoldability(int holdability) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int getHoldability() {
        return java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        log.trace("public Savepoint setSavepoint() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        log.trace("public Savepoint setSavepoint(String name) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        log.trace("public void rollback(Savepoint savepoint) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        log.trace("public void releaseSavepoint(Savepoint savepoint) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        log.trace("public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        log.trace("public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        log.trace("public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        log.trace("public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        log.trace("public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        log.trace("public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Clob createClob() throws SQLException {
        log.trace("public Clob createClob() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Blob createBlob() throws SQLException {
        log.trace("public Blob createBlob() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public NClob createNClob() throws SQLException {
        log.trace("public NClob createNClob() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        log.trace("public SQLXML createSQLXML() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isValid(final int timeout) {
        return !isClosed();
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        log.trace("public void setClientInfo(String name, String value) throws SQLClientInfoException {");
        throw new SQLClientInfoException();
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        log.trace("public void setClientInfo(Properties properties) throws SQLClientInfoException {");
        throw new SQLClientInfoException();
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        log.trace("public String getClientInfo(String name) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        log.trace("public Properties getClientInfo() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Array createArrayOf(
            final String typeName,
            final Object[] elements) throws SQLException {
        if ("varchar".equalsIgnoreCase(typeName)) {
            return new Array(typeName, Types.VARCHAR, elements);
        }
        log.trace("public Array createArrayOf(String typeName, Object[] elements) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        log.trace("public Struct createStruct(String typeName, Object[] attributes) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        log.trace("public void setSchema(String schema) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public String getSchema() throws SQLException {
        log.trace("public String getSchema() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        log.trace("public void abort(Executor executor) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        log.trace("public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int getNetworkTimeout() {
        return 0;
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
