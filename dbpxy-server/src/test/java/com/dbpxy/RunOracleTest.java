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

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;

@Slf4j
@ActiveProfiles({"integration", "oracle"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@EnabledIfEnvironmentVariable(named = "running.from.local.environment", matches = ".+")
class RunOracleTest {
    @RegisterExtension
    static OracleExtension oracle = new OracleExtension(1521);
    @DynamicPropertySource
    static void configureProperties(
            final DynamicPropertyRegistry registry) {
        log.info("oracle port: {}", oracle.getMappedPort());
        registry.add("ORACLE_USER", oracle::getUser);
        registry.add("ORACLE_PASSWORD", oracle::getPassword);
        registry.add("ORACLE_DATABASE", oracle::getDatabase);
        registry.add("ORACLE_PORT", oracle::getMappedPort);
    }

    final AtomicBoolean sigtermReceived = new AtomicBoolean(false);

    @Test
    void run() {
        log.info("app is running in testing mode");

        final Thread shutdownHook = new Thread(() -> {
            sigtermReceived.set(true);
        });

        Runtime.getRuntime().addShutdownHook(shutdownHook);

        await()
                .pollInterval(Duration.ofSeconds(1))
                .atMost(Duration.ofDays(1))
                .untilTrue(sigtermReceived);
    }
}
