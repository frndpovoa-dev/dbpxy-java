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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;

@Slf4j
@RequiredArgsConstructor
public class ToxiproxyExtension implements BeforeAllCallback, AfterAllCallback {
    private final int[] ports;
    private ToxiproxyContainer container;

    public String getHost() {
        return container.getHost();
    }

    public int getControlPort() {
        return container.getControlPort();
    }

    public int getMappedPort(final int port) {
        return container.getMappedPort(port);
    }

    @Override
    public void afterAll(final ExtensionContext context) throws Exception {
        if (container != null) {
            container.stop();
        }
    }

    @Override
    public void beforeAll(final ExtensionContext context) throws Exception {
        container = new ToxiproxyContainer(DockerImageName
                .parse("ghcr.io/shopify/toxiproxy")
                .withTag(context.getConfigurationParameter("toxiproxy.version").orElse("latest"))
        ) {
        }
                .withAccessToHost(true)
                .withNetwork(Network.SHARED)
                .withReuse(false)
                .withLogConsumer(new Slf4jLogConsumer(log))
                .withSharedMemorySize(1000 * 1000 * 512L)
        ;
        final ArrayList<Integer> exposedPorts = new ArrayList<>();
        exposedPorts.add(8474);
        for (final int port : ports) {
            exposedPorts.add(port);
        }
        container.setExposedPorts(exposedPorts);
        container.start();

        log.info("toxiproxy server started on {}:{}", container.getHost(), container.getControlPort());
    }
}
