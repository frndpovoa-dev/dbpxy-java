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
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.sql.Date;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class PreparedStatement extends Statement implements java.sql.PreparedStatement {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Value NULL_VALUE = Value.newBuilder()
            .setCode(ValueCode.NULL)
            .setData(ValueNull.newBuilder().build().toByteString())
            .build();

    private final Map<Integer, Object> params = new HashMap<>();
    private final String sql;

    public PreparedStatement(
            final Connection connection,
            final long defaultQueryTimeoutInMs,
            final String sql
    ) {
        super(connection, defaultQueryTimeoutInMs);
        this.sql = sql;
    }

    private static Value nullSafeArgToValue(final Object value) throws SQLException {
        if (value == null) {
            return NULL_VALUE;
        }
        if (value instanceof Byte) {
            return Value.newBuilder()
                    .setCode(ValueCode.INT32)
                    .setData(ValueInt32.newBuilder().setValue((Byte) value).build().toByteString())
                    .build();
        }
        if (value instanceof Short) {
            return Value.newBuilder()
                    .setCode(ValueCode.INT32)
                    .setData(ValueInt32.newBuilder().setValue((Short) value).build().toByteString())
                    .build();
        }
        if (value instanceof Integer) {
            return Value.newBuilder()
                    .setCode(ValueCode.INT32)
                    .setData(ValueInt32.newBuilder().setValue((Integer) value).build().toByteString())
                    .build();
        }
        if (value instanceof Long) {
            return Value.newBuilder()
                    .setCode(ValueCode.INT64)
                    .setData(ValueInt64.newBuilder().setValue((Long) value).build().toByteString())
                    .build();
        }
        if (value instanceof String) {
            return Value.newBuilder()
                    .setCode(ValueCode.STRING)
                    .setData(ValueString.newBuilder().setValue((String) value).build().toByteString())
                    .build();
        }
        if (value instanceof Boolean) {
            return Value.newBuilder()
                    .setCode(ValueCode.BOOL)
                    .setData(ValueBool.newBuilder().setValue((Boolean) value).build().toByteString())
                    .build();
        }
        if (value instanceof Float) {
            return Value.newBuilder()
                    .setCode(ValueCode.FLOAT64)
                    .setData(ValueFloat64.newBuilder().setValue(Float.toString((Float) value)).build().toByteString())
                    .build();
        }
        if (value instanceof Double) {
            return Value.newBuilder()
                    .setCode(ValueCode.FLOAT64)
                    .setData(ValueFloat64.newBuilder().setValue(Double.toString((Double) value)).build().toByteString())
                    .build();
        }
        if (value instanceof BigDecimal) {
            return Value.newBuilder()
                    .setCode(ValueCode.FLOAT64)
                    .setData(ValueFloat64.newBuilder().setValue(((BigDecimal) value).toString()).build().toByteString())
                    .build();
        }
        if (value instanceof java.sql.Date) {
            return dateValue(LocalDate.ofInstant(Instant.ofEpochMilli(((java.sql.Date) value).getTime()), ZoneId.systemDefault()));
        }
        if (value instanceof java.sql.Time) {
            return timeValue(LocalTime.ofInstant(Instant.ofEpochMilli(((java.sql.Time) value).getTime()), ZoneId.systemDefault()));
        }
        if (value instanceof java.sql.Timestamp) {
            final java.sql.Timestamp v = (java.sql.Timestamp) value;
            return timestampValue(OffsetDateTime.ofInstant(v.toInstant(), ZoneOffset.ofHoursMinutes(-v.getTimezoneOffset() / 60, -v.getTimezoneOffset() % 60)));
        }
        if (value instanceof java.util.Date) {
            final java.util.Date v = (java.util.Date) value;
            return timestampValue(OffsetDateTime.ofInstant(Instant.ofEpochMilli(v.getTime()), ZoneOffset.ofHoursMinutes(-v.getTimezoneOffset() / 60, -v.getTimezoneOffset() % 60)));
        }
        if (value instanceof LocalDate) {
            return dateValue((LocalDate) value);
        }
        if (value instanceof LocalTime) {
            return timeValue((LocalTime) value);
        }
        if (value instanceof OffsetDateTime) {
            return timestampValue((OffsetDateTime) value);
        }
        if (value instanceof LocalDateTime) {
            throw new SQLFeatureNotSupportedException("Use OffsetDateTime");
        }
        if (value instanceof ZonedDateTime) {
            throw new SQLFeatureNotSupportedException("Use OffsetDateTime");
        }
        if (value instanceof byte[]) {
            final byte[] v = (byte[]) value;
            try {
                return Value.newBuilder()
                        .setCode(ValueCode.BYTES)
                        .setData(ValueString.newBuilder()
                                .setValue(OBJECT_MAPPER.writeValueAsString(v))
                                .build()
                                .toByteString())
                        .build();
            } catch (final JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        throw new SQLFeatureNotSupportedException();
    }

    private static Value dateValue(final LocalDate value) {
        return Value.newBuilder()
                .setCode(ValueCode.DATE)
                .setData(ValueTime.newBuilder()
                        .setValue(value.format(DateTimeFormatter.ISO_LOCAL_DATE))
                        .build()
                        .toByteString())
                .build();
    }

    private static Value timeValue(final LocalTime value) {
        return Value.newBuilder()
                .setCode(ValueCode.TIME)
                .setData(ValueTime.newBuilder()
                        .setValue(value.format(DateTimeFormatter.ISO_LOCAL_TIME))
                        .build()
                        .toByteString())
                .build();
    }

    private static Value timestampValue(final OffsetDateTime value) {
        return Value.newBuilder()
                .setCode(ValueCode.TIMESTAMP)
                .setData(ValueTime.newBuilder()
                        .setValue(value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        .build()
                        .toByteString())
                .build();
    }

    protected List<Value> paramAsList() throws SQLException {
        final ArrayList<Value> result = new ArrayList<>();
        for (final Object value : params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList()) {
            result.add(nullSafeArgToValue(value));
        }
        return result;
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
    public void setNull(final int parameterIndex, final int sqlType) {
        params.put(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) {
        params.put(parameterIndex, x);
    }

    @Override
    public void setByte(int parameterIndex, byte x) {
        params.put(parameterIndex, x);
    }

    @Override
    public void setShort(int parameterIndex, short x) {
        params.put(parameterIndex, x);
    }

    @Override
    public void setInt(int parameterIndex, int x) {
        params.put(parameterIndex, x);
    }

    @Override
    public void setLong(int parameterIndex, long x) {
        params.put(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) {
        params.put(parameterIndex, x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) {
        params.put(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) {
        params.put(parameterIndex, x);
    }

    @Override
    public void setString(int parameterIndex, String x) {
        params.put(parameterIndex, x);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) {
        params.put(parameterIndex, x);
    }

    @Override
    public void setDate(final int parameterIndex, final Date x) {
        params.put(parameterIndex, x);
    }

    @Override
    public void setTime(final int parameterIndex, final Time x) {
        params.put(parameterIndex, x);
    }

    @Override
    public void setTimestamp(final int parameterIndex, final Timestamp x) {
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
    public void clearParameters() {
        params.clear();
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        log.trace("public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {");
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setObject(final int parameterIndex, final Object x) throws SQLException {
        log.trace("public void setObject(int parameterIndex, Object x) throws SQLException {");
        params.put(parameterIndex, x);
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
    public void setArray(int parameterIndex, java.sql.Array x) {
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
    public void setTimestamp(final int parameterIndex, final Timestamp x, final Calendar cal) {
        params.put(parameterIndex, Optional.ofNullable(x)
                .map(t -> OffsetDateTime.ofInstant(t.toInstant(), (cal == null) ? ZoneId.systemDefault() : cal.getTimeZone().toZoneId()))
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
