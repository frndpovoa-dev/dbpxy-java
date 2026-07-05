package com.dbpxy.service;

/*-
 * #%L
 * dbpxy-server
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

import com.dbpxy.exception.UnsupportedInReadOnlyModeException;
import com.dbpxy.exception.UnsupportedInWriteOnlyModeException;
import com.dbpxy.jdbc.ConnectionProxy;
import com.dbpxy.proto.*;
import org.apache.commons.pool2.ObjectPool;

import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;

public abstract class DatabaseNoOperation implements DatabaseOperation {

    @Override
    public void setTransaction(final Transaction transaction) {
        // Do nothing
    }

    @Override
    public void openConnection(final ObjectPool<ConnectionProxy> connectionPool, final ExecutorService taskExecutor) {
        // Do nothing
    }

    @Override
    public void closeConnection() {
        // Do nothing
    }

    @Override
    public OffsetDateTime beginTransaction(final DatabaseOperationProp databaseOperationProp) {
        return OffsetDateTime.now();
    }

    @Override
    public ExecuteResult execute(final ExecuteConfig config) throws UnsupportedInReadOnlyModeException {
        throw new UnsupportedInReadOnlyModeException();
    }

    @Override
    public QueryResult query(final QueryConfig config) throws UnsupportedInWriteOnlyModeException {
        throw new UnsupportedInWriteOnlyModeException();
    }

    @Override
    public QueryResult next(final NextConfig config) throws UnsupportedInWriteOnlyModeException {
        throw new UnsupportedInWriteOnlyModeException();
    }

    @Override
    public void closeResultSet(final NextConfig config) throws UnsupportedInWriteOnlyModeException {
        throw new UnsupportedInWriteOnlyModeException();
    }

    @Override
    public boolean commitTransaction() {
        return false;
    }

    @Override
    public boolean rollbackTransaction() {
        return false;
    }
}
