package dev.frndpovoa.project1.databaseproxy.jdbc;

/*-
 * #%L
 * database-proxy-lib
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

import dev.frndpovoa.project1.databaseproxy.ConnectionHolder;
import dev.frndpovoa.project1.databaseproxy.config.DatabaseProxyDataSourceProperties;
import dev.frndpovoa.project1.databaseproxy.config.DatabaseProxyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

@Slf4j
@RequiredArgsConstructor
public class DataSource implements javax.sql.DataSource {
    private final DatabaseProxyDataSourceProperties databaseProxyDataSourceProperties;
    private final DatabaseProxyProperties databaseProxyProperties;
    private PrintWriter printWriter;
    private int loginTimeout;


    @Override
    public Connection getConnection() throws SQLException {
        final Connection connection = new Connection(databaseProxyProperties, databaseProxyDataSourceProperties);
        ConnectionHolder.pushConnection(connection);
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return printWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        this.printWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return Logger.getLogger(DataSource.class.getName());
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }
}
