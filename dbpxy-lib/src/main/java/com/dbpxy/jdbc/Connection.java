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
import com.dbpxy.util.DatabaseUtils;
import com.dbpxy.util.TransactionUtils;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Connection implements java.sql.Connection {
    private static final String MDC_TRANSACTION_ID = "dbpxy.tx.id";
    private static final List<Transaction.Status> ACTIVE_TRANSACTION_STATUSES = List.of(Transaction.Status.NOT_STARTED, Transaction.Status.ACTIVE, Transaction.Status.JOINED);
    private static final long DEFAULT_QUERY_TIMEOUT_IN_MS = Duration.ofMinutes(1).toMillis();

    @Getter
    @EqualsAndHashCode.Include
    private final String id = UUID.randomUUID().toString().replace("-", "");
    private final ConnectionHolder connectionHolder;
    private final DbpxyProperties dbpxyProperties;
    private final Optional<DbpxyDatasourceProperties> maybeDbpxyDatasourceProperties;
    private final String dbpxyCertPath;
    private final Deque<Transaction> transactions = new ArrayDeque<>(5);

    private ManagedChannel channel;
    @Getter(AccessLevel.PACKAGE)
    private DbpxyGrpc.DbpxyBlockingStub blockingStub;
    @Setter
    private long transactionTimeoutInMs = DEFAULT_QUERY_TIMEOUT_IN_MS;
    private boolean autoCommit = true;
    private boolean readOnly = false;
    private boolean closed = false;
    private String catalog;

    private synchronized void initGrpcChannelIfNeeded() throws SQLException {
        if (channel != null) {
            return;
        }
        try (final InputStream cert = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(dbpxyCertPath))) {
            final ChannelCredentials credentials = TlsChannelCredentials.newBuilder()
                    .trustManager(cert)
                    .build();
            this.channel = Grpc.newChannelBuilderForAddress(
                            dbpxyProperties.getHostname(),
                            dbpxyProperties.getPort(),
                            credentials)
                    .build();
            this.blockingStub = DbpxyGrpc.newBlockingStub(channel);
            log.debug("gRPC opened");
        } catch (final IOException e) {
            throw new SQLException(e);
        }
    }

    public String getTransactionId() throws SQLException {
        try {
            return TransactionUtils.format(getOrCreateTransaction(true));
        } catch (final URISyntaxException e) {
            throw new SQLException(e);
        }
    }

    public String getReadOnlyTransactionId() throws SQLException {
        try {
            return TransactionUtils.formatToReadOnly(getOrCreateTransaction(true));
        } catch (final URISyntaxException e) {
            throw new SQLException(e);
        }
    }

    public String getReadWriteTransactionId() throws SQLException {
        try {
            return TransactionUtils.formatToReadWrite(getOrCreateTransaction(true));
        } catch (final URISyntaxException e) {
            throw new SQLException(e);
        }
    }

    public String getWriteOnlyTransactionId() throws SQLException {
        try {
            return TransactionUtils.formatToWriteOnly(getOrCreateTransaction(true));
        } catch (final URISyntaxException e) {
            throw new SQLException(e);
        }
    }

    public synchronized Transaction getOrCreateTransaction(final boolean create) throws SQLException {
        if (create) {
            initGrpcChannelIfNeeded();

            transactions.removeIf(transaction ->
                    !ACTIVE_TRANSACTION_STATUSES.contains(transaction.getStatus()));

            if (transactions.isEmpty() && maybeDbpxyDatasourceProperties.isPresent()) {
                final ConnectionString connectionString = ConnectionString.newBuilder()
                        .setUrl(maybeDbpxyDatasourceProperties.get().getUrl())
                        .addAllProps(maybeDbpxyDatasourceProperties.get().getProps().stream()
                                .map(prop -> ConnectionStringProp.newBuilder()
                                        .setName(prop.getName())
                                        .setValue(prop.getValue())
                                        .build())
                                .collect(Collectors.toList()))
                        .build();

                try {
                    final Transaction transaction = blockingStub
                            .beginTransaction(BeginTransactionConfig.newBuilder()
                                    .setConnectionString(connectionString)
                                    .setTimeoutInMs(transactionTimeoutInMs)
                                    .setAutoCommit(autoCommit)
                                    .setReadOnly(readOnly)
                                    .build());
                    pushTransaction(transaction);
                    log.debug("transaction began");
                } catch (final RuntimeException e) {
                    throw new SQLException(e);
                }
            }
        }
        return transactions.peek();
    }

    private void pushTransaction(@NonNull final Transaction transaction) {
        transactions.push(transaction);
        MDC.put(MDC_TRANSACTION_ID, DatabaseUtils.getMaskedId(transaction.getId()));
    }

    private void popTransaction(@NonNull final Transaction transaction) {
        transactions.removeIf(it ->
                Objects.equals(it.getId(), transaction.getId()) && Objects.equals(it.getNode(), transaction.getNode()));
        try {
            Optional.ofNullable(getOrCreateTransaction(false))
                    .ifPresentOrElse(it -> MDC.put(MDC_TRANSACTION_ID, DatabaseUtils.getMaskedId(it.getId())), () -> MDC.remove(MDC_TRANSACTION_ID));
        } catch (final SQLException e) {
            log.error(e.getMessage(), e);
            MDC.remove(MDC_TRANSACTION_ID);
        }
    }

    public <T> T doWithSharedTransaction(
            final String transactionId,
            final Callable<T> callable) throws Exception {
        if (transactionId == null) {
            return callable.call();
        }
        final Transaction transaction = TransactionUtils.tryParse(transactionId);
        try {
            joinSharedTransaction(transaction);
            return callable.call();
        } finally {
            leaveSharedTransaction(transaction);
        }
    }

    public synchronized void joinSharedTransaction(@Nullable final Transaction transaction) throws SQLException {
        if (transaction == null) {
            throw new SQLException("unable to join shared transaction. invalid value: null");
        }
        if (log.isTraceEnabled()) {
            log.trace("joinSharedTransaction({})", DatabaseUtils.getMaskedId(transaction.getId()));
        }
        initGrpcChannelIfNeeded();
        pushTransaction(transaction.toBuilder()
                .setStatus(Transaction.Status.JOINED)
                .build());
        log.debug("joined transaction");
    }

    public void leaveSharedTransaction(@Nullable final Transaction transaction) throws SQLException {
        if (transaction == null) {
            throw new SQLException("unable to leave shared transaction. invalid value: null");
        }
        if (log.isTraceEnabled()) {
            log.trace("leaveSharedTransaction({})", DatabaseUtils.getMaskedId(transaction.getId()));
        }
        popTransaction(transaction);
        log.debug("left transaction");
    }

    public Connection(
            final ConnectionHolder connectionHolder,
            final DbpxyProperties dbpxyProperties,
            final Optional<DbpxyDatasourceProperties> maybeDbpxyDatasourceProperties,
            final String dbpxyCertPath
    ) throws SQLException {
        this.connectionHolder = connectionHolder;
        this.dbpxyProperties = dbpxyProperties;
        this.maybeDbpxyDatasourceProperties = maybeDbpxyDatasourceProperties;
        this.dbpxyCertPath = dbpxyCertPath;
        connectionHolder.pushConnection(this);
        log.debug("connection lazyly opened");
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

    @Override
    public void commit() throws SQLException {
        final Transaction transaction = getOrCreateTransaction(false);
        if (transaction == null) {
            log.debug("commit skipped: no transaction");
        } else {
            try {
                if (List.of(Transaction.Status.NOT_STARTED, Transaction.Status.ACTIVE).contains(transaction.getStatus())) {
                    try {
                        blockingStub.commitTransaction(transaction);
                        log.debug("transaction commited");
                    } catch (final RuntimeException e) {
                        throw new SQLException(e);
                    }
                }
            } finally {
                popTransaction(transaction);
            }
        }
    }

    @Override
    public void rollback() throws SQLException {
        final Transaction transaction = getOrCreateTransaction(false);
        if (transaction == null) {
            log.debug("rollback skipped: no transaction");
        } else {
            try {
                if (List.of(Transaction.Status.NOT_STARTED, Transaction.Status.ACTIVE).contains(transaction.getStatus())) {
                    try {
                        blockingStub.rollbackTransaction(transaction);
                        log.debug("transaction rolled back");
                    } catch (final RuntimeException e) {
                        throw new SQLException(e);
                    }
                }
            } finally {
                popTransaction(transaction);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        final Transaction transaction = getOrCreateTransaction(false);
        try {
            if (transaction == null) {
                // Do nothing
            } else if (transaction.getStatus() == Transaction.Status.JOINED) {
                log.debug("close skipped on shared transaction");
            } else if (List.of(Transaction.Status.NOT_STARTED, Transaction.Status.ACTIVE).contains(transaction.getStatus())) {
                if (autoCommit) {
                    commit();
                }
                log.debug("closed");
            }
            if (channel != null) {
                if (channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)) {
                    log.debug("gRPC closed");
                }
            }
        } catch (final InterruptedException e) {
            throw new SQLException(e);
        } finally {
            this.closed = true;
            this.channel = null;
            connectionHolder.popConnection(this);
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
        // Do nothing
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
