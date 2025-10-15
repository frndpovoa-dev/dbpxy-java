package com.dbpxy.jdbc;

/*-
 * #%L
 * dbpxy-lib
 * $Id:$
 * $HeadURL:$
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

import com.dbpxy.proto.QueryResult;
import com.dbpxy.proto.Row;
import com.dbpxy.proto.Value;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class ResultSetMetaData implements java.sql.ResultSetMetaData {
    private final QueryResult queryResult;

    protected Optional<Row> getFirstRow() {
        return Optional.ofNullable(queryResult)
                .filter(it -> it.getRowsCount() > 0)
                .map(it -> it.getRows(0));
    }

    @Override
    public int getColumnCount() throws SQLException {
        log.trace("public int getColumnCount() throws SQLException {");
        return getFirstRow()
                .map(Row::getColsCount)
                .orElse(0);
    }

    @Override
    public boolean isAutoIncrement(final int column) throws SQLException {
        log.trace("public boolean isAutoIncrement(final int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        log.trace("public boolean isCaseSensitive(int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        log.trace("public boolean isSearchable(int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        log.trace("public boolean isCurrency(int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public int isNullable(int column) throws SQLException {
        log.trace("public int isNullable(int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        log.trace("public boolean isSigned(int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        log.trace("public int getColumnDisplaySize(int column) throws SQLException {");
        return getFirstRow()
                .map(row -> row.getCols(column - 1))
                .map(Value::getSize)
                .orElseThrow(() -> new SQLException("Not supported yet."));
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        log.trace("public String getColumnLabel(int column) throws SQLException {");
        return getFirstRow()
                .map(row -> row.getCols(column - 1))
                .map(Value::getLabel)
                .orElseThrow(() -> new SQLException("Not supported yet."));
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        log.trace("public String getColumnName(int column) throws SQLException {");
        return getFirstRow()
                .map(row -> row.getCols(column - 1))
                .map(Value::getName)
                .orElseThrow(() -> new SQLException("Not supported yet."));
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        log.trace("public String getSchemaName(int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        log.trace("public int getPrecision(int column) throws SQLException {");
        return getFirstRow()
                .map(row -> row.getCols(column - 1))
                .map(Value::getPrecision)
                .orElseThrow(() -> new SQLException("Not supported yet."));
    }

    @Override
    public int getScale(final int column) throws SQLException {
        log.trace("public int getScale(final int column) throws SQLException {");
        return getFirstRow()
                .map(row -> row.getCols(column - 1))
                .map(Value::getScale)
                .orElseThrow(() -> new SQLException("Not supported yet."));
    }

    @Override
    public String getTableName(int column) throws SQLException {
        log.trace("public String getTableName(int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        log.trace("public String getCatalogName(int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return getFirstRow()
                .map(row -> row.getCols(column - 1))
                .map(col -> {
                    switch (col.getCode()) {
                        case INT32 -> {
                            return Types.INTEGER;
                        }
                        case INT64 -> {
                            return Types.BIGINT;
                        }
                        case FLOAT64 -> {
                            return Types.FLOAT;
                        }
                        case BOOL -> {
                            return Types.BOOLEAN;
                        }
                        case BYTES -> {
                            return Types.VARBINARY;
                        }
                        case STRING -> {
                            return Types.VARCHAR;
                        }
                        case TIME -> {
                            return Types.TIMESTAMP;
                        }
                        case NULL -> {
                            return Types.NULL;
                        }
                        default -> {
                            return null;
                        }
                    }
                })
                .orElse(Types.OTHER);
    }

    @Override
    public String getColumnTypeName(final int column) throws SQLException {
        return getFirstRow()
                .map(row -> row.getCols(column - 1))
                .map(col -> {
                    switch (col.getCode()) {
                        case INT32 -> {
                            return "INTEGER";
                        }
                        case INT64 -> {
                            return "BIGINT";
                        }
                        case FLOAT64 -> {
                            return "FLOAT";
                        }
                        case BOOL -> {
                            return "BOOLEAN";
                        }
                        case BYTES -> {
                            return "VARBINARY";
                        }
                        case STRING -> {
                            return "VARCHAR";
                        }
                        case TIME -> {
                            return "TIMESTAMP";
                        }
                        case NULL -> {
                            return "NULL";
                        }
                        default -> {
                            return null;
                        }
                    }
                })
                .orElse("OTHER");
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        log.trace("public boolean isReadOnly(int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        log.trace("public boolean isWritable(int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        log.trace("public boolean isDefinitelyWritable(int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        log.trace("public String getColumnClassName(int column) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        log.trace("public <T> T unwrap(Class<T> iface) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        log.trace("public boolean isWrapperFor(Class<?> iface) throws SQLException {");
        throw new SQLException("Not supported yet.");
    }
}
