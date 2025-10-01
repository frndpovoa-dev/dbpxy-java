package com.dbpxy.repository;

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

import com.dbpxy.BaseIntTest;
import com.dbpxy.ConnectionHolder;
import com.dbpxy.bo.TestBo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Transactional(timeout = 60)
class TestRepositoryIntTest extends BaseIntTest {
    @Autowired
    private TestRepository repository;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private ConnectionHolder connectionHolder;

    public static final TestBo TEST_1 = TestBo.builder()
            .id(1L)
            .name("Hello World! 1")
            .groupName("repo")
            .build();
    public static final TestBo TEST_2 = TestBo.builder()
            .id(2L)
            .name("Hello World! 2")
            .groupName("repo")
            .build();

    @Test
    @Sql(value = "classpath:com.dbpxy.repository/TestRepositoryIntTest.sql", config = @SqlConfig(dataSource = "dataSource"))
    void testJpaUsingSharedTransaction() {
        final String transactionId = connectionHolder.getConnection().getTransactionId();
        log.debug("Tx transactionId({})", transactionId);

        log.debug("Read before insert using JPA");
        log.debug("{}", repository.findByGroupName("repo"));

        log.debug("Insert");
        log.debug("{}", repository.saveAndFlush(TEST_2));

        log.debug("Read after insert using JPA");
        log.debug("{}", repository.findByGroupName("repo"));

        log.debug("Read after insert using API");
        final List<TestBo> apiResponse = restTemplate.exchange("http://localhost:9091/api/v1/test/list?group=repo", HttpMethod.GET, new HttpEntity<>(
                MultiValueMap.fromSingleValue(Map.of("X-Transaction-Id", transactionId))), new ParameterizedTypeReference<List<TestBo>>() {
        }).getBody();
        log.debug("{}", apiResponse);
        assertThat(apiResponse)
                .isNotEmpty()
                .containsExactly(TEST_1, TEST_2);

        Optional<TestBo> test1Bo = repository.findById(TEST_1.getId());
        assertThat(test1Bo.isPresent())
                .isTrue();
        assertThat(test1Bo.get())
                .isNotNull()
                .isEqualTo(TEST_1);

        Optional<TestBo> test2Bo = repository.findById(TEST_2.getId());
        assertThat(test2Bo.isPresent())
                .isTrue();
        assertThat(test2Bo.get())
                .isNotNull()
                .isEqualTo(TEST_2);
    }
}
