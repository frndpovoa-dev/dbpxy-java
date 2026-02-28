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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Getter
@Setter
public class PreparedStatement extends Statement implements java.sql.PreparedStatement {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final Map<Integer, Object> params = new HashMap<>();
    private String sql;

    public PreparedStatement(
            final Connection connection,
            final Integer defaultQueryTimeoutInMs,
            final String sql
    ) {
        super(connection, defaultQueryTimeoutInMs);
        this.sql = sql;
    }

    protected Value nullSafeArgToValue(final Object value) {
        return Optional.ofNullable(value)
                .map(it -> {
                    if (it instanceof Short v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.INT32)
                                .setData(ValueInt32.newBuilder().setValue(v).build().toByteString())
                                .build();
                    } else if (it instanceof Integer v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.INT32)
                                .setData(ValueInt32.newBuilder().setValue(v).build().toByteString())
                                .build();
                    } else if (it instanceof Long v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.INT64)
                                .setData(ValueInt64.newBuilder().setValue(v).build().toByteString())
                                .build();
                    } else if (it instanceof String v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.STRING)
                                .setData(ValueString.newBuilder().setValue(v).build().toByteString())
                                .build();
                    } else if (it instanceof Boolean v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.BOOL)
                                .setData(ValueBool.newBuilder().setValue(v).build().toByteString())
                                .build();
                    } else if (it instanceof Double v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.FLOAT64)
                                .setData(ValueFloat64.newBuilder().setValue(Double.toString(v)).build().toByteString())
                                .build();
                    } else if (it instanceof BigDecimal v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.FLOAT64)
                                .setData(ValueFloat64.newBuilder().setValue(v.toString()).build().toByteString())
                                .build();
                    } else if (it instanceof Timestamp v) {
                        return Value.newBuilder()
                                .setCode(ValueCode.TIME)
                                .setData(ValueTime.newBuilder()
                                        .setValue(OffsetDateTime.ofInstant(v.toInstant(), ZoneId.systemDefault())
                                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                                        .build()
                                        .toByteString())
                                .build();
                    } else if (it instanceof Array v) {
                        try {
                            return Value.newBuilder()
                                    .setCode(ValueCode.ARRAY)
                                    .setData(ValueTime.newBuilder()
                                            .setValue(OBJECT_MAPPER.writeValueAsString(Map.of(
                                                    "baseType", v.getBaseType(),
                                                    "baseTypeName", v.getBaseTypeName(),
                                                    "array", v.getArray()
                                            )))
                                            .build()
                                            .toByteString())
                                    .build();
                        } catch (final SQLException | JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
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
                .sorted(Map.Entry.comparingByKey())
                .map(e -> nullSafeArgToValue(e.getValue()))
                .toList();
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        return executeQuery(sql, paramAsList());
    }

    @Override
    public int executeUpdate() throws SQLException {
        return executeUpdate(sql, paramAsList());
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
        log.trace("public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        log.trace("public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        log.trace("public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void clearParameters() throws SQLException {
        params.clear();
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        log.trace("public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        log.trace("public void setObject(int parameterIndex, Object x) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean execute() throws SQLException {
        return execute(sql);
    }

    @Override
    public void addBatch() throws SQLException {
        log.trace("public void addBatch() throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        log.trace("public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        log.trace("public void setRef(int parameterIndex, Ref x) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        log.trace("public void setBlob(int parameterIndex, Blob x) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        log.trace("public void setClob(int parameterIndex, Clob x) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setArray(int parameterIndex, java.sql.Array x) throws SQLException {
        params.put(parameterIndex, x);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        log.trace("public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        log.trace("public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        params.put(parameterIndex, Optional.ofNullable(x)
                .map(t -> OffsetDateTime.ofInstant(t.toInstant(), ZoneId.systemDefault()))
                .map(odt -> Optional.ofNullable(cal)
                        .map(calendar -> odt.atZoneSameInstant(calendar.getTimeZone().toZoneId()))
                        .orElseGet(odt::toZonedDateTime))
                .map(ZonedDateTime::toInstant)
                .map(Timestamp::from)
                .orElse(null));
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        log.trace("public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        log.trace("public void setURL(int parameterIndex, URL x) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        log.trace("public void setRowId(int parameterIndex, RowId x) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        log.trace("public void setNString(int parameterIndex, String value) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        log.trace("public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        log.trace("public void setNClob(int parameterIndex, NClob value) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        log.trace("public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        log.trace("public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        log.trace("public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        log.trace("public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        log.trace("public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        log.trace("public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        log.trace("public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        log.trace("public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        log.trace("public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        log.trace("public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        log.trace("public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        log.trace("public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        log.trace("public void setClob(int parameterIndex, Reader reader) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        log.trace("public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        log.trace("public void setNClob(int parameterIndex, Reader reader) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }
}
