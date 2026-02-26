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
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Connection implements java.sql.Connection {
    private static final List<Transaction.Status> ACTIVE_TRANSACTION_STATUSES = List.of(Transaction.Status.ACTIVE, Transaction.Status.JOINED);
    private static final Pattern TRANSACTION_ID_PATTERN = Pattern.compile("^(.+?)@(.+)$");
    private static final Integer DEFAULT_QUERY_TIMEOUT_IN_MS = 60_000;
    @EqualsAndHashCode.Include
    private final String id = UUID.randomUUID().toString();
    private final ChannelCredentials credentials;
    private final DbpxyProperties dbpxyProperties;
    private final ConnectionHolder connectionHolder;
    private final ConnectionString connectionString;
    private ManagedChannel channel;
    private DbpxyGrpc.DbpxyBlockingStub blockingStub;
    private boolean autoCommit = true;
    private boolean closed = false;
    private boolean readOnly = false;
    private String catalog;

    private final Deque<Transaction> transactions = new ArrayDeque<>();

    public String getTransactionId() {
        return Optional.ofNullable(getTransaction(true, DEFAULT_QUERY_TIMEOUT_IN_MS))
                .map(transaction -> transaction.getId() + "@" + transaction.getNode())
                .orElse(null);
    }

    public synchronized Transaction getTransaction(final boolean create, final Integer timeout) {
        if (create) {
            new ArrayList<>(transactions).stream()
                    .filter(transaction -> !ACTIVE_TRANSACTION_STATUSES.contains(transaction.getStatus()))
                    .forEach(this::popTransaction);
            if (transactions.isEmpty()) {
                final Transaction transaction = blockingStub
                        .beginTransaction(BeginTransactionConfig.newBuilder()
                                .setConnectionString(connectionString)
                                .setTimeout(timeout)
                                .setAutoCommit(autoCommit)
                                .setReadOnly(readOnly)
                                .build());
                pushTransaction(transaction);
            }
        }
        return Optional.of(transactions)
                .filter(Predicate.not(Deque::isEmpty))
                .map(Deque::peek)
                .orElse(null);
    }

    public synchronized void pushTransaction(final Transaction transaction) {
        transactions.push(transaction);
    }

    public synchronized void popTransaction(final Transaction transaction) {
        transactions.removeIf(it ->
                Objects.equals(it.getId(), transaction.getId())
                        && Objects.equals(it.getNode(), transaction.getNode()));
    }

    public void replaceTransaction(final Transaction out, final Transaction in) {
        popTransaction(out);
        pushTransaction(in);
    }

    public void joinSharedTransaction(final String transactionId) throws SQLException {
        log.debug("joinSharedTransaction({})", transactionId);
        final Matcher m = TRANSACTION_ID_PATTERN.matcher(transactionId);
        if (!m.matches()) {
            throw new SQLException("Invalid transaction id format");
        }
        final Transaction transaction = Transaction.newBuilder()
                .setId(m.group(1))
                .setNode(m.group(2))
                .setStatus(Transaction.Status.JOINED)
                .build();
        log.debug("reconnected({} -> {})", id, transaction.getNode());
        pushTransaction(transaction);
    }

    public void leaveSharedTransaction(final String transactionId) throws SQLException {
        log.debug("leaveSharedTransaction({})", transactionId);
        final Matcher m = TRANSACTION_ID_PATTERN.matcher(transactionId);
        if (!m.matches()) {
            throw new SQLException("Invalid transaction id format");
        }
        popTransaction(Transaction.newBuilder()
                .setId(m.group(1))
                .setNode(m.group(2))
                .build());
    }

    public Connection(
            final ConnectionHolder connectionHolder,
            final DbpxyProperties dbpxyProperties,
            final DbpxyDatasourceProperties dbpxyDatasourceProperties,
            final String dbpxyCertPath
    ) throws SQLException {
        try {
            try (final InputStream cert = new ClassPathResource(dbpxyCertPath).getInputStream()) {
                this.credentials = TlsChannelCredentials.newBuilder()
                        .trustManager(cert)
                        .build();
            }
            this.channel = Grpc.newChannelBuilderForAddress(
                            dbpxyProperties.getHostname(),
                            dbpxyProperties.getPort(),
                            credentials)
                    .build();
            this.blockingStub = DbpxyGrpc.newBlockingStub(channel);
            this.dbpxyProperties = dbpxyProperties;
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
            log.debug("open({})", id);
        } catch (final IOException e) {
            throw new SQLException(e);
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        return new Statement(this, DEFAULT_QUERY_TIMEOUT_IN_MS);
    }

    @Override
    public PreparedStatement prepareStatement(final String sql) throws SQLException {
        return new PreparedStatement(this, DEFAULT_QUERY_TIMEOUT_IN_MS, sql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        log.trace("public CallableStatement prepareCall(String sql) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public String nativeSQL(final String sql) throws SQLException {
        return sql;
    }

    @Override
    public void setAutoCommit(final boolean autoCommit) throws SQLException {
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return autoCommit;
    }

    private String getMaskedId(final String id) {
        return id.substring(0, 32);
    }

    @Override
    public void commit() throws SQLException {
        final Transaction transaction = getTransaction(false, 0);
        if (readOnly || transaction == null) {
            log.debug("commit skipped (conn: {})", id);
        } else {
            log.debug("commit(conn: {}, tx: {})", id, getMaskedId(transaction.getId()));
            if (transaction.getStatus() == Transaction.Status.ACTIVE) {
                final Transaction newTransaction = blockingStub.commitTransaction(transaction);
                replaceTransaction(transaction, newTransaction);
            }
        }
    }

    @Override
    public void rollback() throws SQLException {
        final Transaction transaction = getTransaction(false, 0);
        if (readOnly || transaction == null) {
            log.debug("rollback skipped (conn: {})", id);
        } else {
            log.debug("rollback(conn: {}, tx: {})", id, getMaskedId(transaction.getId()));
            if (transaction.getStatus() == Transaction.Status.ACTIVE) {
                final Transaction newTransaction = blockingStub.rollbackTransaction(transaction);
                replaceTransaction(transaction, newTransaction);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        final Transaction transaction = getTransaction(false, 0);
        if (transaction.getStatus() != Transaction.Status.JOINED) {
            log.debug("close({})", id);
            try {
                channel.shutdownNow();
            } finally {
                this.closed = true;
                connectionHolder.popConnection(this);
            }
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public PgDatabaseMetaData getMetaData() throws SQLException {
        return new PgDatabaseMetaData(this);
    }

    @Override
    public void setReadOnly(final boolean readOnly) throws SQLException {
        this.readOnly = readOnly;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return readOnly;
    }

    @Override
    public void setCatalog(final String catalog) throws SQLException {
        this.catalog = catalog;
    }

    @Override
    public String getCatalog() throws SQLException {
        return catalog;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        log.trace("public void setTransactionIsolation(int level) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return java.sql.Connection.TRANSACTION_READ_COMMITTED;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
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
            final int resultSetConcurrency) throws SQLException {
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
    public int getHoldability() throws SQLException {
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
    public boolean isValid(final int timeout) throws SQLException {
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
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        if (Objects.equals("varchar", typeName)) {
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
    public int getNetworkTimeout() throws SQLException {
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
    public boolean isWrapperFor(final Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }
}
