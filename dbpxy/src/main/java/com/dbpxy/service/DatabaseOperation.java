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

import com.dbpxy.exception.UnsupportedInReadOnlyModeException;
import com.dbpxy.jdbc.ConnectionProxy;
import com.dbpxy.proto.*;
import org.apache.commons.pool2.ObjectPool;

import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;

public interface DatabaseOperation {

    long getTimeoutInMs();

    Transaction getTransaction();

    void setTransaction(Transaction transaction);

    void openConnection(
            ObjectPool<ConnectionProxy> connectionPool,
            ExecutorService taskExecutor);

    void closeConnection();

    OffsetDateTime beginTransaction(BeginTransactionConfig config);

    ExecuteResult execute(ExecuteConfig config) throws UnsupportedInReadOnlyModeException;

    QueryResult query(QueryConfig config);

    QueryResult next(NextConfig config);

    void closeResultSet(NextConfig config);

    boolean commitTransaction();

    boolean rollbackTransaction();
}
