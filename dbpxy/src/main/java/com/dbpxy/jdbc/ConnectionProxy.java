package com.dbpxy.jdbc;

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

import lombok.Getter;
import lombok.experimental.Delegate;
import stormpot.BasePoolable;
import stormpot.Slot;

import java.sql.SQLException;

public class ConnectionProxy extends BasePoolable implements java.sql.Connection {
    @Getter
    @Delegate(types = java.sql.Connection.class)
    private final java.sql.Connection connection;

    public ConnectionProxy(
            final Slot slot,
            final java.sql.Connection connection) {
        super(slot);
        this.connection = connection;
    }

    @Override
    public void close() throws SQLException {
        if (!connection.getAutoCommit()) {
            connection.rollback();
            connection.setAutoCommit(true);
        }
        connection.clearWarnings();
        slot.release(this);
    }
}
