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
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Connection implements java.sql.Connection {
    private static final Pattern transactionIdPattern = Pattern.compile("^(.+?)@(.+)$");
    private static final Integer DEFAULT_QUERY_TIMEOUT = 1_000;
    @EqualsAndHashCode.Include
    private final String id = UUID.randomUUID().toString();
    private final ManagedChannel channel;
    private final DbpxyGrpc.DbpxyBlockingStub blockingStub;
    private final ConnectionHolder connectionHolder;
    private final ConnectionString connectionString;
    private boolean autoCommit = true;
    private boolean closed = false;
    private boolean readOnly = false;
    private String catalog;

    private final Stack<Transaction> transactions = new Stack<>();

    public String getTransactionId() {
        return Optional.ofNullable(getTransaction(true, DEFAULT_QUERY_TIMEOUT))
                .map(transaction -> transaction.getId() + "@" + transaction.getNode())
                .orElse(null);
    }

    public Transaction getTransaction(final boolean create, final Integer timeout) {
        if (create) {
            final List<Transaction.Status> activeTransactionStatuses = List.of(
                    Transaction.Status.ACTIVE,
                    Transaction.Status.JOINED);
            new ArrayList<>(transactions).stream()
                    .filter(existingTransaction -> !activeTransactionStatuses.contains(existingTransaction.getStatus()))
                    .forEach(this::popTransaction);
            if (transactions.isEmpty()) {
                final Transaction transaction = blockingStub
                        .beginTransaction(BeginTransactionConfig.newBuilder()
                                .setConnectionString(connectionString)
                                .setTimeout(timeout)
                                .setReadOnly(readOnly)
                                .build());
                pushTransaction(transaction);
            }
        }
        return Optional.of(transactions)
                .filter(Predicate.not(Stack::isEmpty))
                .map(Stack::peek)
                .orElse(null);
    }

    public void pushTransaction(final Transaction transaction) {
        transactions.push(transaction);
    }

    public boolean popTransaction(final Transaction transaction) {
        return transactions.remove(transaction);
    }

    public void replaceTransaction(final Transaction out, final Transaction in) {
        popTransaction(out);
        pushTransaction(in);
    }

    public void joinSharedTransaction(final String transactionId) throws SQLException {
        log.debug("joinSharedTransaction({})", transactionId);
        final Matcher m = transactionIdPattern.matcher(transactionId);
        if (!m.matches()) {
            throw new SQLException("Invalid transaction id format");
        }
        final Transaction transaction = Transaction.newBuilder()
                .setId(m.group(1))
                .setNode(m.group(2))
                .setStatus(Transaction.Status.JOINED)
                .build();
        pushTransaction(transaction);
    }

    public Connection(
            final ConnectionHolder connectionHolder,
            final DbpxyProperties dbpxyProperties,
            final DbpxyDatasourceProperties dbpxyDatasourceProperties,
            final String dbpxyCertPath
    ) throws SQLException {
        try {
            final ChannelCredentials credentials = TlsChannelCredentials.newBuilder()
                    .trustManager(new ClassPathResource(dbpxyCertPath).getInputStream())
                    .build();
            this.channel = Grpc.newChannelBuilderForAddress(
                            dbpxyProperties.getHostname(),
                            dbpxyProperties.getPort(),
                            credentials)
                    .build();
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
            log.debug("open({})", id);
        } catch (final IOException e) {
            throw new SQLException(e);
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        return new Statement(this, DEFAULT_QUERY_TIMEOUT);
    }

    @Override
    public PreparedStatement prepareStatement(final String sql) throws SQLException {
        return new PreparedStatement(this, DEFAULT_QUERY_TIMEOUT, sql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
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
        return id.replaceFirst("^(.{32}).*$", "$1");
    }

    @Override
    public void commit() throws SQLException {
        if (readOnly) {
            log.debug("commit skipped (conn: {})", id);
        } else {
            final Transaction transaction = getTransaction(false, 0);
            log.debug("commit(conn: {}, tx: {})", id, getMaskedId(transaction.getId()));
            if (transaction.getStatus() == Transaction.Status.ACTIVE) {
                final Transaction newTransaction = getBlockingStub().commitTransaction(transaction);
                replaceTransaction(transaction, newTransaction);
            }
        }
    }

    @Override
    public void rollback() throws SQLException {
        if (readOnly) {
            log.debug("rollback skipped (conn: {})", id);
        } else {
            final Transaction transaction = getTransaction(false, 0);
            log.debug("rollback(conn: {}, tx: {})", id, getMaskedId(transaction.getId()));
            if (transaction.getStatus() == Transaction.Status.ACTIVE) {
                final Transaction newTransaction = getBlockingStub().rollbackTransaction(transaction);
                replaceTransaction(transaction, newTransaction);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        log.debug("close({})", id);
        try {
            connectionHolder.popConnection(this);
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            throw new SQLException(e);
        } finally {
            this.closed = true;
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
    public void setCatalog(String catalog) throws SQLException {
        this.catalog = catalog;
    }

    @Override
    public String getCatalog() throws SQLException {
        return catalog;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
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
        throw new UnsupportedOperationException("Not supported yet.");
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
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int getHoldability() throws SQLException {
        return java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Clob createClob() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        // FIXME
        return true;
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String getSchema() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return 0;
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
