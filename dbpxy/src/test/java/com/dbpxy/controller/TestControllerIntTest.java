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
import com.dbpxy.config.Headers;
import com.dbpxy.dto.TestDto;
import com.dbpxy.proto.*;
import com.dbpxy.util.TransactionUtils;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.StopWatch;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"app.dbpxy.ddl-auto=create"}
)
class TestControllerIntTest extends BaseIntTest {
    private static final String LIST_GROUP_WEB_URL = "http://localhost:9091/api/v1/test/list?group=web";
    private static final String INSERT_URL = "http://localhost:9091/api/v1/test/insert";

    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private DbpxyProperties dbpxyProperties;
    @Autowired
    private DbpxyDatasourceProperties dbpxyDatasourceProperties;
    private ManagedChannel channel;
    private DbpxyGrpc.DbpxyBlockingStub blockingStub;
    private Transaction tx1Transaction;
    private String tx1Id;
    private Transaction tx2Transaction;
    private String tx2Id;

    private final StopWatch stopWatch = new StopWatch();

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
                .addAllProps(dbpxyDatasourceProperties.getProps().stream()
                        .map(prop -> ConnectionStringProp.newBuilder()
                                .setName(prop.getName())
                                .setValue(prop.getValue())
                                .build())
                        .toList())
                .build();
        this.tx1Transaction = blockingStub
                .beginTransaction(BeginTransactionConfig.newBuilder()
                        .setConnectionString(connectionString)
                        .setTimeoutInMs(60_000 * 10)
                        .setReadOnly(false)
                        .build());
        this.tx2Transaction = blockingStub
                .beginTransaction(BeginTransactionConfig.newBuilder()
                        .setConnectionString(connectionString)
                        .setTimeoutInMs(60_000 * 10)
                        .setReadOnly(false)
                        .build());

        this.tx1Id = TransactionUtils.format(tx1Transaction);
        log.debug("tx 1 {}", tx1Id);

        this.tx2Id = TransactionUtils.format(tx2Transaction);
        log.debug("tx 2 {}", tx2Id);
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

        log.debug("read before insert using tx 1");
        assertThat(listGroupWeb(tx1Id, listTypeReference))
                .isEmpty();

        log.debug("read before insert using tx 2");
        assertThat(listGroupWeb(tx2Id, listTypeReference))
                .isEmpty();

        log.debug("insert using tx 1");
        final TestDto insertTx1 = TestDto.builder()
                .id(2025L)
                .name("Hello World!")
                .groupName("web")
                .doubleValue(2.0)
                .bigdecimalValue(new BigDecimal("20.0000000000000000000000000"))
                .build();
        final TestDto insertTx1a = TestDto.builder()
                .id(2026L)
                .name("Hello World! from server side")
                .groupName("web")
                .doubleValue(2.0)
                .bigdecimalValue(new BigDecimal("20.0000000000000000000000000"))
                .build();
        assertThat(insert(tx1Id, TestDto.class, insertTx1))
                .isNotNull()
                .isEqualTo(insertTx1);

        log.debug("concurrent reads after insert using tx 1 and tx 2");
        stopWatch.run(() -> {
            try (final ForkJoinPool forkJoinPool = new ForkJoinPool(5)) {
                forkJoinPool.execute(() -> IntStream.range(0, 500).parallel().forEach(ignored -> {
                    assertThat(listGroupWeb(tx1Id, listTypeReference))
                            .isNotEmpty()
                            .containsExactly(insertTx1, insertTx1a);
                    assertThat(listGroupWeb(tx2Id, listTypeReference))
                            .isNotEmpty()
                            .containsExactly(insertTx1a);
                }));
            }
        });
        log.info("concurrent reads on tx 1 and tx 2 ran for {}ms", stopWatch.getDuration().toMillis());
        assertThat(stopWatch.getDuration())
                .isLessThan(Duration.ofSeconds(10));
    }

    private @Nullable <T> T insert(
            final String transactionId,
            final Class<T> clazz,
            final T data) {
        return restTemplate.exchange(INSERT_URL, HttpMethod.POST,
                        new HttpEntity<>(data, HttpHeaders.readOnlyHttpHeaders(MultiValueMap.fromSingleValue(Map.of(Headers.TRANSACTION, transactionId)))),
                        clazz)
                .getBody();
    }

    private @Nullable <T> T listGroupWeb(
            final String transactionId,
            final ParameterizedTypeReference<T> typeReference) {
        return restTemplate.exchange(LIST_GROUP_WEB_URL, HttpMethod.GET,
                        new HttpEntity<>(HttpHeaders.readOnlyHttpHeaders(MultiValueMap.fromSingleValue(Map.of(Headers.TRANSACTION, transactionId)))),
                        typeReference)
                .getBody();
    }
}
