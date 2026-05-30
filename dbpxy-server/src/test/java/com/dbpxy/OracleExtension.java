package com.dbpxy;

/*-
 * #%L
 * dbpxy-server
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
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

@Slf4j
@NoArgsConstructor
public class OracleExtension implements BeforeAllCallback, AfterAllCallback {
    private final int port = 1521;
    @Getter
    private final String database = "FREEPDB1";
    private final String password = "oracle";

    private OracleContainer container;
    private int mappedPort;

    public String getUser() {
        return container.getUsername();
    }

    public String getPassword() {
        return container.getPassword();
    }

    public String getMappedPort() {
        return "" + container.getMappedPort(port);
    }

    public OracleExtension(final int port) {
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
        container = new OracleContainer(DockerImageName
                .parse("gvenzl/oracle-free")
                .withTag(context.getConfigurationParameter("oracle.version").orElse("latest"))
        ) {
        }
                .withReuse(false)
                .withSharedMemorySize(1000 * 1000 * 512L)
                .withEnv("ORACLE_PASSWORD", password)
        ;
        if (mappedPort > 0) {
            container.setPortBindings(List.of(String.format("%s:%s", mappedPort, port)));
        } else {
            container.setExposedPorts(List.of(port));
        }
        container.start();

        log.info("oracle server started on {}:{}", container.getHost(), container.getMappedPort(port));
    }
}
