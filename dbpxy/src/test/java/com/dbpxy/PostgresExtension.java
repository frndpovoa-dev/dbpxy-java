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
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class PostgresExtension implements BeforeAllCallback {
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private GenericContainer<?> postgresql;

    @Override
    public void beforeAll(final ExtensionContext context) throws Exception {
        if (started.get()) {
            return;
        } else {
            started.set(true);
        }

        final String database = "postgres";
        final int port = 5432;
        final String username = "postgres";
        final String password = "postgres";
        postgresql = new GenericContainer(DockerImageName
                .parse("bitnamilegacy/postgresql")
                .withTag(context.getConfigurationParameter("postgresql.version").orElse("latest"))
        ) {
        }
                .withSharedMemorySize(1024 * 1024 * 512L)
                .withEnv("POSTGRESQL_DATABASE", database)
                .withEnv("POSTGRESQL_PASSWORD", password)
        ;

        postgresql.setPortBindings(List.of(String.format("%s:%s", port, port)));
        postgresql.start();

        System.setProperty("POSTGRESQL_HOSTNAME", postgresql.getHost());
        System.setProperty("POSTGRESQL_PORT", "" + postgresql.getMappedPort(port));
        System.setProperty("POSTGRESQL_DATABASE", database);
        System.setProperty("POSTGRESQL_USERNAME", username);
        System.setProperty("POSTGRESQL_PASSWORD", password);

        log.info("Postgresql server started on {}:{}", postgresql.getHost(), postgresql.getMappedPort(port));
    }
}
