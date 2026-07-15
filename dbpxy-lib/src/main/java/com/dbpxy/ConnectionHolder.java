package com.dbpxy;

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

import com.dbpxy.exception.UnsupportedInReadOnlyModeException;
import com.dbpxy.jdbc.Connection;
import com.dbpxy.jdbc.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Callable;

@Slf4j
@RequiredArgsConstructor
public class ConnectionHolder {
    private static final String MDC_CONNECTION_ID = "dbpxy.conn.id";
    private static final String MDC_TRANSACTION_ID = "dbpxy.tx.id";
    private final ObjectProvider<DataSource> dataSourceProvider;

    protected void entityManagerFlush() {
        // Do nothing
    }

    protected void entityManagerClear() {
        // Do nothing
    }

    @SuppressWarnings({"java:S1143", "java:S1163"})
    public <T> T doWithSharedTransaction(
            final String transactionId,
            final Callable<T> callable) throws Exception {

        entityManagerFlush();
        entityManagerClear();

        return getConnection().doWithSharedTransaction(
                transactionId,
                () -> {
                    try {
                        return callable.call();
                    } finally {
                        try {
                            entityManagerFlush();
                        } catch (final Exception e) {
                            if (e instanceof UnsupportedInReadOnlyModeException) {
                                throw (UnsupportedInReadOnlyModeException) e;
                            }
                            if (e.getCause() instanceof UnsupportedInReadOnlyModeException) {
                                throw (UnsupportedInReadOnlyModeException) e.getCause();
                            }
                            throw e;
                        } finally {
                            entityManagerClear();
                        }
                    }
                });
    }

    public @Nullable Connection getConnection() {
        final org.springframework.jdbc.datasource.ConnectionHolder holder = (org.springframework.jdbc.datasource.ConnectionHolder) TransactionSynchronizationManager.getResource(dataSourceProvider.getObject());
        return (Connection) holder.getConnection();
    }

    public void clear() {
        MDC.remove(MDC_CONNECTION_ID);
        MDC.remove(MDC_TRANSACTION_ID);
    }
}
