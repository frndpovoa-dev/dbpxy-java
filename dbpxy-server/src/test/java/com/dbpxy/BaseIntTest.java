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

import com.dbpxy.config.DbpxyDatasourceProperties;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@ActiveProfiles({"integration", "postgresql"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public abstract class BaseIntTest {
    @RegisterExtension
    static PostgresExtension postgresql = new PostgresExtension();
    @RegisterExtension
    static ToxiproxyExtension toxiproxy = new ToxiproxyExtension(new int[]{5432, 9092});
    protected static ToxiproxyClient toxiproxyClient;

    @DynamicPropertySource
    static void configureProperties(
            final DynamicPropertyRegistry registry) {
        registry.add("POSTGRESQL_USER", postgresql::getUser);
        registry.add("POSTGRESQL_PASSWORD", postgresql::getPassword);
        registry.add("POSTGRESQL_DATABASE", postgresql::getDatabase);
        registry.add("POSTGRESQL_PORT", () -> toxiproxy.getMappedPort(5432));
        registry.add("GRPC_PORT", () -> toxiproxy.getMappedPort(9092));
    }

    @Autowired
    protected DbpxyDatasourceProperties dataSourceProperties;

    @BeforeAll
    static void beforeAll() throws Exception {
        Testcontainers.exposeHostPorts(9090);
        toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        toxiproxyClient.createProxy("dbpxy", "0.0.0.0:9092", "host.docker.internal:9090");
        toxiproxyClient.createProxy("postgres", "0.0.0.0:5432", "postgres:" + postgresql.getPort());
    }

    @AfterAll
    static void afterAll() throws Exception {
        toxiproxyClient.reset();
    }

    protected long usedHeapSize() {
        System.gc();
        final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        return memoryMXBean.getHeapMemoryUsage().getUsed();
    }

    protected void assertHeapSizeDiff(final long memoryBefore, final long acceptableMemoryDiff) {
        System.gc();
        final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        final long memoryAfter = memoryMXBean.getHeapMemoryUsage().getUsed();
        final long memoryDiff = memoryAfter - memoryBefore;
        log.info("heap size diff: {} bytes", memoryDiff);
        assertTrue(memoryDiff < acceptableMemoryDiff, "potential memory leak detected: " + memoryDiff + " bytes");
    }
}
