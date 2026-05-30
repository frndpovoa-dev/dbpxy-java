package com.dbpxy.jdbc;

/*-
 * #%L
 * dbpxy-lib
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2025 - 2026 Fernando Lemes Povoa
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

import lombok.RequiredArgsConstructor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Map;

@RequiredArgsConstructor
public class Array implements java.sql.Array {
    private final String baseTypeName;
    private final int baseType;
    private final Object[] array;

    @Override
    public String getBaseTypeName() {
        return baseTypeName;
    }

    @Override
    public int getBaseType() {
        return baseType;
    }

    @Override
    public Object getArray() {
        return array;
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Object getArray(final long index, final int count) throws SQLException {
        if (index < 1) {
            throw new SQLException("Array index must be at least 1");
        }
        final int startIndex = (int) index - 1;
        if (startIndex >= array.length) {
            throw new SQLException("Array index out of bounds: " + index);
        }
        final int endIndex = Math.min(startIndex + count, array.length);
        return Arrays.copyOfRange(array, startIndex, endIndex);
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public ResultSet getResultSet(long index, int count) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void free() {
        // No resources to free
    }
}
