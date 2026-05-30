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

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

@Slf4j
@NoArgsConstructor
public class PostgresExtension implements BeforeAllCallback, AfterAllCallback {
    private final int port = 5432;
    @Getter
    private final String database = "postgres";
    @Getter
    private final String user = "postgres";
    @Getter
    private final String password = "postgres";

    private GenericContainer<?> container;
    private int mappedPort;

    public String getMappedPort() {
        return "" + container.getMappedPort(port);
    }

    public PostgresExtension(final int port) {
        this.mappedPort = port;
    }

    @Override
    public void afterAll(final ExtensionContext context) throws Exception {
        if (container != null) {
            container.stop();
        }
    }

    @Override
    public void beforeAll(final ExtensionContext context) throws Exception {
        container = new GenericContainer(DockerImageName
                .parse("postgres")
                .withTag(context.getConfigurationParameter("postgresql.version").orElse("latest"))
        ) {
        }
                .withReuse(false)
                .withSharedMemorySize(1000 * 1000 * 512L)
                .withEnv("POSTGRES_DB", database)
                .withEnv("POSTGRES_USER", user)
                .withEnv("POSTGRES_PASSWORD", password)
        ;
        if (mappedPort > 0) {
            container.setPortBindings(List.of(String.format("%s:%s", mappedPort, port)));
        } else {
            container.setExposedPorts(List.of(port));
        }
        container.start();

        log.info("postgresql server started on {}:{}", container.getHost(), container.getMappedPort(port));
    }
}
