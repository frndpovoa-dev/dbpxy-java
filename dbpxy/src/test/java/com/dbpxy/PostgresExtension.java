package com.dbpxy;

/*-
 * #%L
 * dbpxy
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

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

@Slf4j
public class PostgresExtension implements BeforeAllCallback, AfterAllCallback {
    private final int port = 5432;
    private final String database = "postgres";
    private final String username = "postgres";
    private final String password = "postgres";

    private GenericContainer<?> postgresql;

    public String getMappedPort() {
        return "" + postgresql.getMappedPort(port);
    }

    @Override
    public void afterAll(final ExtensionContext context) throws Exception {
        if (postgresql != null) {
            postgresql.stop();
        }
    }

    @Override
    public void beforeAll(final ExtensionContext context) throws Exception {
        postgresql = new GenericContainer(DockerImageName
                .parse("postgres")
                .withTag(context.getConfigurationParameter("postgresql.version").orElse("latest"))
        ) {
        }
                .withReuse(false)
                .withSharedMemorySize(1000 * 1000 * 512L)
                .withEnv("POSTGRES_DB", database)
                .withEnv("POSTGRES_USER", username)
                .withEnv("POSTGRES_PASSWORD", password)
        ;

        postgresql.setExposedPorts(List.of(port));
        postgresql.start();

        System.setProperty("POSTGRESQL_HOSTNAME", postgresql.getHost());
        System.setProperty("POSTGRESQL_PORT", "" + postgresql.getMappedPort(port));
        System.setProperty("POSTGRESQL_DATABASE", database);
        System.setProperty("POSTGRESQL_USERNAME", username);
        System.setProperty("POSTGRESQL_PASSWORD", password);

        log.info("postgresql server started on {}:{}", postgresql.getHost(), postgresql.getMappedPort(port));
    }
}
