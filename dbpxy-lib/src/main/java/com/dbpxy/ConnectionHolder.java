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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.concurrent.Callable;

@Slf4j
@RequiredArgsConstructor
public class ConnectionHolder {
    private static final ThreadLocal<ArrayDeque<Connection>> CONNECTIONS = ThreadLocal.withInitial(ArrayDeque::new);

    public void doWithSharedTransaction(
            final String transactionId,
            final Runnable runnable) throws Exception {
        getConnection().doWithSharedTransaction(transactionId, runnable);
    }

    public <T> T doWithSharedTransaction(
            final String transactionId,
            final Callable<T> callable) throws Exception {
        return getConnection().doWithSharedTransaction(transactionId, callable);
    }

    public Connection getConnection() {
        return CONNECTIONS.get().peek();
    }

    public void pushConnection(final Connection connection) {
        CONNECTIONS.get().push(connection);
    }

    public void popConnection(final Connection connection) {
        CONNECTIONS.get().remove(connection);
    }

    public void clear() {
        CONNECTIONS.get().stream()
                .filter(connection -> !connection.isClosed())
                .forEach(connection -> {
            try {
                log.error("dbpxy connection {} did not finish properly, closing it...", connection.getId());
                connection.close();
            } catch (final SQLException e) {
                log.error(e.getMessage(), e);
            }
        });
        CONNECTIONS.remove();
    }
}
