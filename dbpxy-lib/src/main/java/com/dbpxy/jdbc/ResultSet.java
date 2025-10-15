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
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class ResultSet implements java.sql.ResultSet {
    private final Connection connection;
    private final Statement statement;
    private final QueryResult queryResult;
    private ResultSetMetaData resultSetMetaData;
    private int row = -1;
    private int col = 0;

    protected Value getCurrentRowColValue(final int col) {
        this.col = col;
        return queryResult.getRows(row).getCols(col - 1);
    }

    protected String getCurrentRowColValueDataAsString(final int col) throws SQLException {
        this.col = col;
        final Value value = getCurrentRowColValue(col);
        try {
            switch (value.getCode()) {
                case INT32 -> {
                    return Integer.toString(ValueInt32.parseFrom(value.getData()).getValue());
                }
                case INT64 -> {
                    return Long.toString(ValueInt64.parseFrom(value.getData()).getValue());
                }
                case FLOAT64 -> {
                    return Double.toString(ValueFloat64.parseFrom(value.getData()).getValue());
                }
                case BOOL -> {
                    return Boolean.toString(ValueBool.parseFrom(value.getData()).getValue());
                }
                case STRING -> {
                    return ValueString.parseFrom(value.getData()).getValue();
                }
                case TIME -> {
                    return ValueTime.parseFrom(value.getData()).getValue();
                }
                case NULL -> {
                    return null;
                }
                default -> {
                    return null;
                }
            }
        } catch (InvalidProtocolBufferException e) {
            throw new SQLException(e);
        }
    }

    @Override
    public boolean next() throws SQLException {
        log.trace("public boolean next() throws SQLException {");
        if (row + 1 < queryResult.getRowsCount()) {
            row++;
            return true;
        }
        return false;
    }

    @Override
    public void close() throws SQLException {
        log.trace("public void close() throws SQLException {");
        connection.getBlockingStub().closeResultSet(NextConfig.newBuilder()
                .setQueryResultId(queryResult.getId())
                .setTransaction(Optional.ofNullable(connection.getTransaction(false, 0))
                        .orElseGet(Transaction::getDefaultInstance))
                .build());
    }

    @Override
    public boolean wasNull() throws SQLException {
        log.trace("public boolean wasNull() throws SQLException {");
        return getCurrentRowColValue(col).getCode() == ValueCode.NULL;
    }

    @Override
    public String getString(final int columnIndex) throws SQLException {
        log.trace("public String getString(final int columnIndex) throws SQLException {");
        return getCurrentRowColValueDataAsString(columnIndex);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        log.trace("public boolean getBoolean(int columnIndex) throws SQLException {");
        return Optional.ofNullable(getCurrentRowColValueDataAsString(columnIndex))
                .map(Boolean::parseBoolean)
                .orElse(false);
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        log.trace("public byte getByte(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        log.trace("public short getShort(int columnIndex) throws SQLException {");
        return Optional.ofNullable(getCurrentRowColValueDataAsString(columnIndex))
                .map(Short::parseShort)
                .orElse((short) 0);
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        log.trace("public int getInt(int columnIndex) throws SQLException {");
        return Optional.ofNullable(getCurrentRowColValueDataAsString(columnIndex))
                .map(Integer::parseInt)
                .orElse(0);
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        log.trace("public long getLong(int columnIndex) throws SQLException {");
        return Optional.ofNullable(getCurrentRowColValueDataAsString(columnIndex))
                .map(Long::parseLong)
                .orElse(0L);
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        log.trace("public float getFloat(int columnIndex) throws SQLException {");
        return Optional.ofNullable(getCurrentRowColValueDataAsString(columnIndex))
                .map(Float::parseFloat)
                .orElse(0.0F);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        log.trace("public double getDouble(int columnIndex) throws SQLException {");
        return Optional.ofNullable(getCurrentRowColValueDataAsString(columnIndex))
                .map(Double::parseDouble)
                .orElse(0.0);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        log.trace("public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {");
        return Optional.ofNullable(getCurrentRowColValueDataAsString(columnIndex))
                .map(BigDecimal::new)
                .orElse(null);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        log.trace("public byte[] getBytes(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        log.trace("public Date getDate(int columnIndex) throws SQLException {");
        return Optional.ofNullable(getCurrentRowColValueDataAsString(columnIndex))
                .map(Date::valueOf)
                .orElse(null);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        log.trace("public Time getTime(int columnIndex) throws SQLException {");
        return Optional.ofNullable(getCurrentRowColValueDataAsString(columnIndex))
                .map(Time::valueOf)
                .orElse(null);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        log.trace("public Timestamp getTimestamp(int columnIndex) throws SQLException {");
        return Optional.ofNullable(getCurrentRowColValueDataAsString(columnIndex))
                .map(Timestamp::valueOf)
                .orElse(null);
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        log.trace("public InputStream getAsciiStream(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        log.trace("public InputStream getUnicodeStream(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        log.trace("public InputStream getBinaryStream(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        log.trace("public String getString(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        log.trace("public boolean getBoolean(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        log.trace("public byte getByte(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        log.trace("public short getShort(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        log.trace("public int getInt(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        log.trace("public long getLong(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        log.trace("public float getFloat(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        log.trace("public double getDouble(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        log.trace("public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        log.trace("public byte[] getBytes(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        log.trace("public Date getDate(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        log.trace("public Time getTime(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        log.trace("public Timestamp getTimestamp(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        log.trace("public InputStream getAsciiStream(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        log.trace("public InputStream getUnicodeStream(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        log.trace("public InputStream getBinaryStream(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        log.trace("public SQLWarning getWarnings() throws SQLException {");
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        log.trace("public void clearWarnings() throws SQLException {");
    }

    @Override
    public String getCursorName() throws SQLException {
        log.trace("public String getCursorName() throws SQLException {");
        return queryResult.getId();
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        if (resultSetMetaData == null) {
            this.resultSetMetaData = new ResultSetMetaData(queryResult);
        }
        return resultSetMetaData;
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        log.trace("public Object getObject(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        log.trace("public Object getObject(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        log.trace("public int findColumn(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        log.trace("public Reader getCharacterStream(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        log.trace("public Reader getCharacterStream(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        log.trace("public BigDecimal getBigDecimal(int columnIndex) throws SQLException {");
        return BigDecimal.valueOf(Double.parseDouble(getCurrentRowColValueDataAsString(columnIndex)));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        log.trace("public BigDecimal getBigDecimal(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        log.trace("public boolean isBeforeFirst() throws SQLException {");
        return row < 0;
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        log.trace("public boolean isAfterLast() throws SQLException {");
        return row >= queryResult.getRowsCount();
    }

    @Override
    public boolean isFirst() throws SQLException {
        log.trace("public boolean isFirst() throws SQLException {");
        return row == 0;
    }

    @Override
    public boolean isLast() throws SQLException {
        log.trace("public boolean isLast() throws SQLException {");
        return row == queryResult.getRowsCount() - 1;
    }

    @Override
    public void beforeFirst() throws SQLException {
        log.trace("public void beforeFirst() throws SQLException {");
        this.row = -1;
    }

    @Override
    public void afterLast() throws SQLException {
        log.trace("public void afterLast() throws SQLException {");
        this.row = queryResult.getRowsCount();
    }

    @Override
    public boolean first() throws SQLException {
        log.trace("public boolean first() throws SQLException {");
        if (queryResult.getRowsCount() == 0) {
            return false;
        }
        this.row = 0;
        return true;
    }

    @Override
    public boolean last() throws SQLException {
        log.trace("public boolean last() throws SQLException {");
        if (queryResult.getRowsCount() == 0) {
            return false;
        }
        this.row = queryResult.getRowsCount() - 1;
        return true;
    }

    @Override
    public int getRow() throws SQLException {
        log.trace("public int getRow() throws SQLException {");
        return row;
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        log.trace("public boolean absolute(int row) throws SQLException {");
        if (row >= 0 && row < queryResult.getRowsCount()) {
            this.row = row;
            return true;
        }
        return false;
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        log.trace("public boolean relative(int rows) throws SQLException {");
        final int newRow = row + rows;
        if (newRow >= 0 && newRow < queryResult.getRowsCount()) {
            this.row = newRow;
            return true;
        }
        return false;
    }

    @Override
    public boolean previous() throws SQLException {
        log.trace("public boolean previous() throws SQLException {");
        if (row > 0) {
            row--;
            return true;
        }
        return false;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        log.trace("public void setFetchDirection(int direction) throws SQLException {");
        log.trace("setFetchDirection");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public int getFetchDirection() throws SQLException {
        log.trace("public int getFetchDirection() throws SQLException {");
        return java.sql.ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        log.trace("public void setFetchSize(int rows) throws SQLException {");
        log.trace("setFetchSize");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public int getFetchSize() throws SQLException {
        log.trace("public int getFetchSize() throws SQLException {");
        log.trace("getFetchSize");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public int getType() throws SQLException {
        log.trace("public int getType() throws SQLException {");
        return java.sql.ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        log.trace("public int getConcurrency() throws SQLException {");
        return java.sql.ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        log.trace("public boolean rowUpdated() throws SQLException {");
        return false;
    }

    @Override
    public boolean rowInserted() throws SQLException {
        log.trace("public boolean rowInserted() throws SQLException {");
        return false;
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        log.trace("public boolean rowDeleted() throws SQLException {");
        return false;
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        log.trace("public void updateNull(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        log.trace("public void updateBoolean(int columnIndex, boolean x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        log.trace("public void updateByte(int columnIndex, byte x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        log.trace("public void updateShort(int columnIndex, short x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        log.trace("public void updateInt(int columnIndex, int x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        log.trace("public void updateLong(int columnIndex, long x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        log.trace("public void updateFloat(int columnIndex, float x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        log.trace("public void updateDouble(int columnIndex, double x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        log.trace("public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        log.trace("public void updateString(int columnIndex, String x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        log.trace("public void updateBytes(int columnIndex, byte[] x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        log.trace("public void updateDate(int columnIndex, Date x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        log.trace("public void updateTime(int columnIndex, Time x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        log.trace("public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        log.trace("public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        log.trace("public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        log.trace("public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        log.trace("public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        log.trace("public void updateObject(int columnIndex, Object x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        log.trace("public void updateNull(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        log.trace("public void updateBoolean(String columnLabel, boolean x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        log.trace("public void updateByte(String columnLabel, byte x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        log.trace("public void updateShort(String columnLabel, short x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        log.trace("public void updateInt(String columnLabel, int x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        log.trace("public void updateLong(String columnLabel, long x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        log.trace("public void updateFloat(String columnLabel, float x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        log.trace("public void updateDouble(String columnLabel, double x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        log.trace("public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        log.trace("public void updateString(String columnLabel, String x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        log.trace("public void updateBytes(String columnLabel, byte[] x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        log.trace("public void updateDate(String columnLabel, Date x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        log.trace("public void updateTime(String columnLabel, Time x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        log.trace("public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        log.trace("public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        log.trace("public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        log.trace("public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        log.trace("public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        log.trace("public void updateObject(String columnLabel, Object x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void insertRow() throws SQLException {
        log.trace("public void insertRow() throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateRow() throws SQLException {
        log.trace("public void updateRow() throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void deleteRow() throws SQLException {
        log.trace("public void deleteRow() throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void refreshRow() throws SQLException {
        log.trace("public void refreshRow() throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        log.trace("public void cancelRowUpdates() throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        log.trace("public void moveToInsertRow() throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        log.trace("public void moveToCurrentRow() throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Statement getStatement() throws SQLException {
        log.trace("public Statement getStatement() throws SQLException {");
        return statement;
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        log.trace("public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        log.trace("public Ref getRef(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        log.trace("public Blob getBlob(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        log.trace("public Clob getClob(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        log.trace("public Array getArray(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        log.trace("public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        log.trace("public Ref getRef(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        log.trace("public Blob getBlob(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        log.trace("public Clob getClob(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        log.trace("public Array getArray(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        log.trace("public Date getDate(int columnIndex, Calendar cal) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        log.trace("public Date getDate(String columnLabel, Calendar cal) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        log.trace("public Time getTime(int columnIndex, Calendar cal) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        log.trace("public Time getTime(String columnLabel, Calendar cal) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        log.trace("public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {");
        return Optional.ofNullable(getCurrentRowColValueDataAsString(columnIndex))
                .filter(text -> !text.trim().isEmpty())
                .map(text -> OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .map(odt -> Optional.ofNullable(cal)
                        .map(calendar -> odt.atZoneSameInstant(calendar.getTimeZone().toZoneId()))
                        .orElseGet(() -> odt.toZonedDateTime()))
                .map(ZonedDateTime::toInstant)
                .map(Timestamp::from)
                .orElse(null);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        log.trace("public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        log.trace("public URL getURL(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        log.trace("public URL getURL(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        log.trace("public void updateRef(int columnIndex, Ref x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        log.trace("public void updateRef(String columnLabel, Ref x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        log.trace("public void updateBlob(int columnIndex, Blob x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        log.trace("public void updateBlob(String columnLabel, Blob x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        log.trace("public void updateClob(int columnIndex, Clob x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        log.trace("public void updateClob(String columnLabel, Clob x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        log.trace("public void updateArray(int columnIndex, Array x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        log.trace("public void updateArray(String columnLabel, Array x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        log.trace("public RowId getRowId(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        log.trace("public RowId getRowId(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        log.trace("public void updateRowId(int columnIndex, RowId x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        log.trace("public void updateRowId(String columnLabel, RowId x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public int getHoldability() throws SQLException {
        log.trace("public int getHoldability() throws SQLException {");
        return java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public boolean isClosed() throws SQLException {
        log.trace("public boolean isClosed() throws SQLException {");
        return false;
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        log.trace("public void updateNString(int columnIndex, String nString) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        log.trace("public void updateNString(String columnLabel, String nString) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        log.trace("public void updateNClob(int columnIndex, NClob nClob) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        log.trace("public void updateNClob(String columnLabel, NClob nClob) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        log.trace("public NClob getNClob(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        log.trace("public NClob getNClob(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        log.trace("public SQLXML getSQLXML(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        log.trace("public SQLXML getSQLXML(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        log.trace("public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        log.trace("public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        log.trace("public String getNString(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        log.trace("public String getNString(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        log.trace("public Reader getNCharacterStream(int columnIndex) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        log.trace("public Reader getNCharacterStream(String columnLabel) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        log.trace("public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        log.trace("public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        log.trace("public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        log.trace("public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        log.trace("public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        log.trace("public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        log.trace("public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        log.trace("public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        log.trace("public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        log.trace("public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        log.trace("public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        log.trace("public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        log.trace("public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        log.trace("public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        log.trace("public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        log.trace("public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        log.trace("public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        log.trace("public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        log.trace("public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        log.trace("public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        log.trace("public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        log.trace("public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        log.trace("public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        log.trace("public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        log.trace("public void updateClob(int columnIndex, Reader reader) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        log.trace("public void updateClob(String columnLabel, Reader reader) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        log.trace("public void updateNClob(int columnIndex, Reader reader) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        log.trace("public void updateNClob(String columnLabel, Reader reader) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        log.trace("public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        log.trace("public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        log.trace("public <T> T unwrap(Class<T> iface) throws SQLException {");
        return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        log.trace("public boolean isWrapperFor(Class<?> iface) throws SQLException {");
        return false;
    }
}
