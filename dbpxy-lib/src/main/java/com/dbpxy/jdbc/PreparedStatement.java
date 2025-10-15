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

import com.dbpxy.proto.*;
import lombok.Getter;
import lombok.Setter;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Getter
@Setter
public class PreparedStatement extends Statement implements java.sql.PreparedStatement {
    private final Map<Integer, Object> params = new HashMap<>();
    private String sql;

    public PreparedStatement(
            final Connection connection,
            final Integer defaultQueryTimeout,
            final String sql
    ) {
        super(connection, defaultQueryTimeout);
        this.sql = sql;
    }

    protected Value nullSafeArgToValue(final Object value) {
        return Optional.ofNullable(value)
                .map(it -> {
                    if (it instanceof Short v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.INT32)
                                .setData(ValueInt32.newBuilder()
                                        .setValue(v)
                                        .build()
                                        .toByteString())
                                .build();
                    } else if (it instanceof Integer v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.INT32)
                                .setData(ValueInt32.newBuilder()
                                        .setValue(v)
                                        .build()
                                        .toByteString())
                                .build();
                    } else if (it instanceof Long v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.INT64)
                                .setData(ValueInt64.newBuilder()
                                        .setValue(v)
                                        .build()
                                        .toByteString())
                                .build();
                    } else if (it instanceof String v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.STRING)
                                .setData(ValueString.newBuilder()
                                        .setValue(v)
                                        .build()
                                        .toByteString())
                                .build();
                    } else if (it instanceof Boolean v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.BOOL)
                                .setData(ValueBool.newBuilder()
                                        .setValue(v)
                                        .build()
                                        .toByteString())
                                .build();
                    } else if (it instanceof Double v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.FLOAT64)
                                .setData(ValueFloat64.newBuilder()
                                        .setValue(v)
                                        .build()
                                        .toByteString())
                                .build();
                    } else if (it instanceof OffsetDateTime v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.TIME)
                                .setData(ValueTime.newBuilder()
                                        .setValue(v.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                                        .build()
                                        .toByteString())
                                .build();
                    }
                    return null;
                })
                .orElse(Value.newBuilder()
                        .setCode(ValueCode.NULL)
                        .setData(ValueNull.newBuilder().build().toByteString())
                        .build());
    }

    protected List<Value> paramAsList() {
        return params.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(e -> nullSafeArgToValue(e.getValue()))
                .toList();
    }

    @Override
    public java.sql.ResultSet executeQuery() throws SQLException {
        final QueryResult result = getConnection().getAutoCommit() ?
                getConnection().getBlockingStub().query(QueryConfig.newBuilder()
                        .setQuery(sql)
                        .setTimeout(getQueryTimeout())
                        .setConnectionString(getConnection().getConnectionString())
                        .addAllArgs(paramAsList())
                        .build())
                : getConnection().getBlockingStub().queryTx(QueryTxConfig.newBuilder()
                .setTransaction(getConnection().getTransaction(true, getQueryTimeout()))
                .setQueryConfig(QueryConfig.newBuilder()
                        .setQuery(sql)
                        .setTimeout(getQueryTimeout())
                        .addAllArgs(paramAsList())
                        .build())
                .build());
        final ResultSet resultSet = new ResultSet(
                getConnection(),
                this,
                result
        );
        super.setResultSet(resultSet);
        return resultSet;
    }

    @Override
    public int executeUpdate() throws SQLException {
        final ExecuteResult result = getConnection().getAutoCommit() ?
                getConnection().getBlockingStub().execute(ExecuteConfig.newBuilder()
                        .setQuery(sql)
                        .setTimeout(getQueryTimeout())
                        .setConnectionString(getConnection().getConnectionString())
                        .addAllArgs(paramAsList())
                        .build())
                : getConnection().getBlockingStub().executeTx(ExecuteTxConfig.newBuilder()
                .setTransaction(getConnection().getTransaction(true, getQueryTimeout()))
                .setExecuteConfig(ExecuteConfig.newBuilder()
                        .setQuery(sql)
                        .setTimeout(getQueryTimeout())
                        .addAllArgs(paramAsList())
                        .build())
                .build());
        return result.getRowsAffected();
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        params.put(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void clearParameters() throws SQLException {
        params.clear();
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean execute() throws SQLException {
        return execute(sql);
    }

    @Override
    public void addBatch() throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return null;
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return null;
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
