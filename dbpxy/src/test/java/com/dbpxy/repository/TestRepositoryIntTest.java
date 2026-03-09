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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Transactional(timeout = 10)
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
            .doubleValue(-1.0)
            .bigdecimalValue(new BigDecimal("10.9999999999999999999999999"))
            .build();
    public static final TestBo TEST_2 = TestBo.builder()
            .id(2L)
            .name("Hello World! 2")
            .groupName("repo")
            .doubleValue(2.0)
            .bigdecimalValue(new BigDecimal("-20.9999999999999999999999999"))
            .build();
    public static final TestBo TEST_2_DIFF_1 = TEST_2.toBuilder()
            .bigdecimalValue(new BigDecimal("-20.999999999999999999999999")) // Precision difference
            .build();

    @Test
    @Sql(value = "classpath:com.dbpxy.repository/TestRepositoryIntTest.sql", config = @SqlConfig(dataSource = "dataSource"))
    void testJpaUsingSharedTransaction() {
        final String transactionId = connectionHolder.getConnection().getTransactionId();
        log.debug("Tx transactionId({})", transactionId);

        log.debug("Read before insert using JPA");
        final List<TestBo> before = repository.findByGroupName("repo");
        assertThat(before)
                .containsExactly(TEST_1);

        log.debug("Insert");
        final TestBo t2 = repository.saveAndFlush(TEST_2);
        assertThat(t2)
                .isEqualTo(TEST_2);

        log.debug("Read after insert using JPA");
        final List<TestBo> after = repository.findByGroupName("repo");
        assertThat(after)
                .isNotEmpty()
                .containsExactly(TEST_1, TEST_2);

        log.debug("Read after insert using API no TX");
        final List<TestBo> apiResponseNoTx = restTemplate.exchange("http://localhost:9091/api/v1/test/list?group=repo", HttpMethod.GET, new HttpEntity<>(
                MultiValueMap.fromSingleValue(Map.of())), new ParameterizedTypeReference<List<TestBo>>() {
        }).getBody();
        assertThat(apiResponseNoTx)
                .isEmpty();

        log.debug("Read after insert using API");
        final List<TestBo> apiResponseTx = restTemplate.exchange("http://localhost:9091/api/v1/test/list?group=repo", HttpMethod.GET, new HttpEntity<>(
                MultiValueMap.fromSingleValue(Map.of("X-Transaction-Id", transactionId))), new ParameterizedTypeReference<List<TestBo>>() {
        }).getBody();
        assertThat(apiResponseTx)
                .isNotEmpty()
                .containsExactly(TEST_1, TEST_2)
                .doesNotContain(TEST_2_DIFF_1);

        Optional<TestBo> t1Opt = repository.findById(TEST_1.getId());
        assertThat(t1Opt)
                .isPresent();
        assertThat(t1Opt.get())
                .isNotNull()
                .isEqualTo(TEST_1);

        Optional<TestBo> t2Opt = repository.findById(TEST_2.getId());
        assertThat(t2Opt)
                .isPresent();
        assertThat(t2Opt.get())
                .isNotNull()
                .isEqualTo(TEST_2)
                .isNotEqualTo(TEST_2_DIFF_1);
    }
}
