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

import com.dbpxy.jdbc.Connection;
import com.dbpxy.util.DatabaseUtils;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.Callable;

@Slf4j
@RequiredArgsConstructor
public class ConnectionHolder {
    private static final String MDC_CONNECTION_ID = "dbpxy.conn.id";
    private static final String MDC_TRANSACTION_ID = "dbpxy.tx.id";
    private static final ThreadLocal<ArrayDeque<Connection>> CONNECTIONS = ThreadLocal.withInitial(ArrayDeque::new);
    @Setter
    private EntityManager entityManager;

    public void doWithSharedTransaction(
            final String transactionId,
            final Runnable runnable) throws Exception {

        entityManager.flush();
        entityManager.clear();

        getConnection().doWithSharedTransaction(
                transactionId,
                () -> {
                    runnable.run();

                    entityManager.flush();
                    entityManager.clear();
                });
    }

    public <T> T doWithSharedTransaction(
            final String transactionId,
            final Callable<T> callable) throws Exception {

        entityManager.flush();
        entityManager.clear();

        return getConnection().doWithSharedTransaction(
                transactionId,
                () -> {
                    final T result = callable.call();

                    entityManager.flush();
                    entityManager.clear();

                    return result;
                });
    }

    public @Nullable Connection getConnection() {
        return CONNECTIONS.get().peek();
    }

    public void pushConnection(final Connection connection) {
        CONNECTIONS.get().push(connection);
        MDC.put(MDC_CONNECTION_ID, DatabaseUtils.getMaskedId(connection.getId()));
    }

    public void popConnection(final Connection connection) {
        CONNECTIONS.get().remove(connection);
        Optional.ofNullable(getConnection())
                .ifPresentOrElse(it -> MDC.put(MDC_CONNECTION_ID, DatabaseUtils.getMaskedId(it.getId())), () -> MDC.remove(MDC_CONNECTION_ID));
    }

    public void clear() {
        CONNECTIONS.get().stream()
                .filter(connection -> !connection.isClosed())
                .forEach(connection -> {
                    try {
                        MDC.put(MDC_CONNECTION_ID, DatabaseUtils.getMaskedId(connection.getId()));
                        log.error("dbpxy connection did not finish properly, closing it...");
                        connection.close();
                    } catch (final SQLException e) {
                        log.error(e.getMessage(), e);
                    }
                });
        CONNECTIONS.remove();
        MDC.remove(MDC_CONNECTION_ID);
        MDC.remove(MDC_TRANSACTION_ID);
    }
}
