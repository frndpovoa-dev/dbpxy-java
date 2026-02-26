package com.dbpxy.controller;

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
import com.dbpxy.config.DbpxyDatasourceProperties;
import com.dbpxy.config.DbpxyProperties;
import com.dbpxy.dto.TestDto;
import com.dbpxy.proto.*;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class TestControllerIntTest extends BaseIntTest {
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private DbpxyProperties dbpxyProperties;
    @Autowired
    private DbpxyDatasourceProperties dbpxyDatasourceProperties;
    private ManagedChannel channel;
    private DbpxyGrpc.DbpxyBlockingStub blockingStub;
    private Transaction tx1Transaction;
    private String tx1TransactionId;
    private Transaction tx2Transaction;
    private String tx2TransactionId;

    @BeforeEach
    void setUp() throws Exception {
        final ChannelCredentials credentials = TlsChannelCredentials.newBuilder()
                .trustManager(new ClassPathResource("certs/cert.pem").getInputStream())
                .build();
        this.channel = Grpc.newChannelBuilderForAddress(
                        dbpxyProperties.getHostname(),
                        dbpxyProperties.getPort(),
                        credentials)
                .build();
        this.blockingStub = DbpxyGrpc
                .newBlockingStub(channel);

        final ConnectionString connectionString = ConnectionString.newBuilder()
                .setUrl(dbpxyDatasourceProperties.getUrl())
                .addAllProps(dbpxyDatasourceProperties.getProps().entrySet().stream()
                        .map(e -> ConnectionStringProp.newBuilder()
                                .setName(e.getKey())
                                .setValue(e.getValue())
                                .build())
                        .toList())
                .build();
        this.tx1Transaction = blockingStub
                .beginTransaction(BeginTransactionConfig.newBuilder()
                        .setConnectionString(connectionString)
                        .setTimeout(60_000 * 10)
                        .setReadOnly(false)
                        .build());
        this.tx2Transaction = blockingStub
                .beginTransaction(BeginTransactionConfig.newBuilder()
                        .setConnectionString(connectionString)
                        .setTimeout(60_000 * 10)
                        .setReadOnly(false)
                        .build());

        this.tx1TransactionId = tx1Transaction.getId() + "@" + tx1Transaction.getNode();
        log.debug("Tx 1 transactionId({})", tx1TransactionId);

        this.tx2TransactionId = tx2Transaction.getId() + "@" + tx2Transaction.getNode();
        log.debug("Tx 2 transactionId({})", tx2TransactionId);
    }

    @AfterEach
    void tearDown() throws Exception {
        blockingStub.rollbackTransaction(tx1Transaction);
        blockingStub.rollbackTransaction(tx2Transaction);
        channel.shutdownNow();
    }

    @Test
    void testApiUsingSharedTransaction() {
        final ParameterizedTypeReference<List<TestDto>> listTypeReference = new ParameterizedTypeReference<>() {
        };

        log.debug("Read before insert using tx 1");
        final List<TestDto> beforeTx1 = restTemplate.exchange("http://localhost:9091/api/v1/test/list?group=web", HttpMethod.GET, new HttpEntity<>(
                MultiValueMap.fromSingleValue(Map.of("X-Transaction-Id", tx1TransactionId))), listTypeReference).getBody();
        assertThat(beforeTx1)
                .isEmpty();

        log.debug("Read before insert using tx 2");
        final List<TestDto> beforeTx2 = restTemplate.exchange("http://localhost:9091/api/v1/test/list?group=web", HttpMethod.GET, new HttpEntity<>(
                MultiValueMap.fromSingleValue(Map.of("X-Transaction-Id", tx2TransactionId))), listTypeReference).getBody();
        assertThat(beforeTx2)
                .isEmpty();

        log.debug("Insert using tx 1");
        final TestDto insertTx1 = TestDto.builder().id(2025L).name("Hello World!").groupName("web").doubleValue(2.0).bigdecimalValue(new BigDecimal("20.0000000000000000000000000")).build();
        final TestDto insertTx1a = TestDto.builder().id(2026L).name("Hello World! from server side").groupName("web").doubleValue(2.0).bigdecimalValue(new BigDecimal("20.0000000000000000000000000")).build();
        final TestDto resultInsertTx1 = restTemplate.exchange("http://localhost:9091/api/v1/test/insert", HttpMethod.POST, new HttpEntity<>(
                insertTx1,
                MultiValueMap.fromSingleValue(Map.of("X-Transaction-Id", tx1TransactionId))), TestDto.class).getBody();
        assertThat(resultInsertTx1)
                .isNotNull()
                .isEqualTo(insertTx1);

        log.debug("Read after insert using tx 1");
        final List<TestDto> afterTx1 = restTemplate.exchange("http://localhost:9091/api/v1/test/list?group=web", HttpMethod.GET, new HttpEntity<>(
                MultiValueMap.fromSingleValue(Map.of("X-Transaction-Id", tx1TransactionId))), listTypeReference).getBody();
        assertThat(afterTx1)
                .isNotEmpty()
                .containsExactly(insertTx1, insertTx1a);

        log.debug("Read after insert using tx 2");
        final List<TestDto> afterTx2 = restTemplate.exchange("http://localhost:9091/api/v1/test/list?group=web", HttpMethod.GET, new HttpEntity<>(
                MultiValueMap.fromSingleValue(Map.of("X-Transaction-Id", tx2TransactionId))), listTypeReference).getBody();
        assertThat(afterTx2)
                .isNotEmpty()
                .containsExactly(insertTx1a);
    }
}
