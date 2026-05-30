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
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Slf4j
//@ActiveProfiles({"integration", "oracle"})
@ActiveProfiles({"integration", "postgresql"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public abstract class BaseIntTest {
    @RegisterExtension
    static OracleExtension oracle = new OracleExtension();
    @RegisterExtension
    static PostgresExtension postgresql = new PostgresExtension();
    @DynamicPropertySource
    static void configureProperties(
            final DynamicPropertyRegistry registry) {
        log.info("oracle port: {}", oracle.getMappedPort());
        registry.add("ORACLE_USER", oracle::getUser);
        registry.add("ORACLE_PASSWORD", oracle::getPassword);
        registry.add("ORACLE_DATABASE", oracle::getDatabase);
        registry.add("ORACLE_PORT", oracle::getMappedPort);

        log.info("postgresql port: {}", postgresql.getMappedPort());
        registry.add("POSTGRESQL_USER", postgresql::getUser);
        registry.add("POSTGRESQL_PASSWORD", postgresql::getPassword);
        registry.add("POSTGRESQL_DATABASE", postgresql::getDatabase);
        registry.add("POSTGRESQL_PORT", postgresql::getMappedPort);
    }
    @Autowired
    protected DbpxyDatasourceProperties dataSourceProperties;
}
