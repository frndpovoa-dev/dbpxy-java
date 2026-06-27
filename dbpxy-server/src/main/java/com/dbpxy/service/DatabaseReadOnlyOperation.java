package com.dbpxy.service;

/*-
 * #%L
 * dbpxy
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

import com.dbpxy.exception.UnsupportedInWriteOnlyModeException;
import com.dbpxy.proto.NextConfig;
import com.dbpxy.proto.QueryConfig;
import com.dbpxy.proto.QueryResult;
import com.dbpxy.proto.Transaction;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DatabaseReadOnlyOperation extends DatabaseNoOperation implements DatabaseOperation {
    @Getter
    private final DatabaseOperation delegate;

    public long getTimeoutInMs() {
        return delegate.getTimeoutInMs();
    }

    @Override
    public DatabaseOperationProp getDatabaseOperationProp() {
        return delegate.getDatabaseOperationProp();
    }

    @Override
    public Transaction getTransaction() {
        return delegate.getTransaction();
    }

    @Override
    public QueryResult query(final QueryConfig config) throws UnsupportedInWriteOnlyModeException {
        return delegate.query(config);
    }

    @Override
    public QueryResult next(final NextConfig config) throws UnsupportedInWriteOnlyModeException {
        return delegate.next(config);
    }

    @Override
    public void closeResultSet(final NextConfig config) throws UnsupportedInWriteOnlyModeException {
        delegate.closeResultSet(config);
    }
}
