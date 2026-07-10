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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import eu.rekawek.toxiproxy.model.toxic.Latency;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Condition;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"app.dbpxy.ddl-auto=create"})
class TestControllerIntTest extends BaseIntTest {
    private static final String LIST_GROUP_WEB_URL = "http://localhost:9091/api/v1/test/list?group=web";
    private static final String INSERT_URL = "http://localhost:9091/api/v1/test/insert";

    @Autowired
    private ObjectMapper objectMapper;
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

    private CompletableFuture<Void> toxiproxyFuture;
    final AtomicBoolean toxiproxyFlag = new AtomicBoolean(false);

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
                        .collect(Collectors.toList()))
                .build();
        this.tx1Transaction = blockingStub
                .beginTransaction(BeginTransactionConfig.newBuilder()
                        .setActivation(BeginTransactionConfig.Activation.LAZY)
                        .setConnectionString(connectionString)
                        .setTimeoutInMs(60_000 * 10)
                        .setReadOnly(false)
                        .build());
        this.tx2Transaction = blockingStub
                .beginTransaction(BeginTransactionConfig.newBuilder()
                        .setActivation(BeginTransactionConfig.Activation.LAZY)
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

        if (toxiproxyFuture != null) {
            toxiproxyFlag.set(false);
            toxiproxyFuture.cancel(true);
            this.toxiproxyFuture = null;
        }
    }

    @Test
    void testApiUsingSharedTransaction() throws Exception {
        log.debug("read before insert using tx 1");
        assertThat(listGroupWeb(tx1Id))
                .isEqualTo("[]");

        log.debug("read before insert using tx 2");
        assertThat(listGroupWeb(tx2Id))
                .isEqualTo("[]");

        final long now = System.currentTimeMillis();

        final TestDto insertTx1 = TestDto.builder()
                .id(2025L)
                .name("Hello World!")
                .groupName("web")
                .booleanValue(true)
                .byteValue((byte) 1)
                .shortValue((short) 2)
                .integerValue(3)
                .longValue(4L)
                .floatValue(5.0F)
                .doubleValue(6.0D)
                .bytesValue("2025".getBytes(StandardCharsets.UTF_8))
                .bigdecimalValue(new BigDecimal("20.0000000000000000000000001"))
                .sqlDateValue(new java.sql.Date(now))
                .sqlTimeValue(new java.sql.Time(now))
                .utilDateValue(new java.util.Date(now))
                .localDateValue(LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()))
                .localTimeValue(LocalTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()))
                .sqlTimestampValue(new java.sql.Timestamp(now))
                .offsetDateTimeValue(OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()))
                .build();

        final TestDto insertTx1ServerSide = TestDto.builder()
                .id(2026L)
                .name("Hello World! from server side")
                .groupName("web")
                .booleanValue(true)
                .byteValue((byte) 2)
                .shortValue((short) 3)
                .integerValue(4)
                .longValue(5L)
                .floatValue(6.0F)
                .doubleValue(7.0)
                .bytesValue("2025 from server side".getBytes(StandardCharsets.UTF_8))
                .bigdecimalValue(new BigDecimal("21.0000000000000000000000001"))
                .sqlDateValue(new java.sql.Date(now))
                .sqlTimeValue(new java.sql.Time(now))
                .sqlTimestampValue(new java.sql.Timestamp(now))
                .utilDateValue(new java.util.Date(now))
                .localDateValue(LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()))
                .localTimeValue(LocalTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()))
                .offsetDateTimeValue(OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()))
                .build();

        log.debug("insert using tx 1");
        assertThat(insert(tx1Id, insertTx1))
                .isNotNull()
                .isEqualTo(objectMapper.writeValueAsString(insertTx1));

        assertThat(listGroupWeb(tx1Id))
                .isNotEmpty()
                .isEqualTo(objectMapper.writeValueAsString(List.of(insertTx1, insertTx1ServerSide)));

        assertThat(listGroupWeb(tx2Id))
                .isNotEmpty()
                .isEqualTo(objectMapper.writeValueAsString(List.of(insertTx1ServerSide)));

        log.debug("concurrent reads after insert using tx 1 and tx 2");

        final long memoryBefore = usedHeapSize();

        try (final ForkJoinPool forkJoinPool = new ForkJoinPool(5)) {
            final Instant start = Instant.now();
            assertThat(forkJoinPool.submit(() -> IntStream.range(0, 2_000).parallel()
                    .map(ignored -> {
                        try {
                            assertThat(listGroupWeb(tx1Id))
                                    .isNotEmpty()
                                    .isEqualTo(objectMapper.writeValueAsString(List.of(insertTx1, insertTx1ServerSide)));
                            assertThat(listGroupWeb(tx2Id))
                                    .isNotEmpty()
                                    .isEqualTo(objectMapper.writeValueAsString(List.of(insertTx1ServerSide)));
                            return 1;
                        } catch (final JsonProcessingException e) {
                            log.error(e.getMessage());
                            throw new RuntimeException(e);
                        }
                    })
                    .sum()))
                    .isNotNull()
                    .succeedsWithin(Duration.ofSeconds(60))
                    .is(new Condition<>(total -> total == 2_000, "Expected 2000 iteration results"));
            final Instant end = Instant.now();
            assertThat(Duration.between(start, end))
                    .isGreaterThanOrEqualTo(Duration.ofSeconds(30))
                    .isLessThanOrEqualTo(Duration.ofSeconds(60));
        }

        // Toxiproxy
        toxiproxyFlag.set(true);
        final Latency latency = toxiproxyClient.getProxy("dbpxy").toxics()
                .latency("latency-toxic", ToxicDirection.UPSTREAM, 1)
                .setJitter(100);
        this.toxiproxyFuture = CompletableFuture.runAsync(() -> {
            try {
                while (toxiproxyFlag.get()) {
                    latency.setLatency(1_250);
                    Thread.sleep(100);
                    latency.setLatency(1);
                    Thread.sleep(30_000);
                }
            } catch (final InterruptedException |
                           IOException e) {
                log.error(e.getMessage());
            }
        });
        try (final ForkJoinPool forkJoinPool = new ForkJoinPool(5)) {
            final Instant start = Instant.now();
            assertThat(forkJoinPool.submit(() -> IntStream.range(0, 2_000).parallel()
                    .map(ignored -> {
                        try {
                            assertThat(listGroupWeb(tx1Id))
                                    .isNotEmpty()
                                    .isEqualTo(objectMapper.writeValueAsString(List.of(insertTx1, insertTx1ServerSide)));
                            assertThat(listGroupWeb(tx2Id))
                                    .isNotEmpty()
                                    .isEqualTo(objectMapper.writeValueAsString(List.of(insertTx1ServerSide)));
                            return 1;
                        } catch (final JsonProcessingException e) {
                            log.error(e.getMessage());
                            throw new RuntimeException(e);
                        }
                    })
                    .sum()))
                    .isNotNull()
                    .succeedsWithin(Duration.ofMinutes(5))
                    .is(new Condition<>(total -> total == 2_000, "Expected 2000 iteration results"));
            final Instant end = Instant.now();
            assertThat(Duration.between(start, end))
                    .isGreaterThanOrEqualTo(Duration.ofMinutes(2))
                    .isLessThanOrEqualTo(Duration.ofMinutes(5));
        }
        toxiproxyFlag.set(false);
        // End toxiproxy

        assertHeapSizeDiff(memoryBefore, 40_000_000);
    }

    private @Nullable <T> String insert(
            final String transactionId,
            final T data) {
        return restTemplate.exchange(INSERT_URL, HttpMethod.POST,
                        new HttpEntity<>(data, HttpHeaders.readOnlyHttpHeaders(MultiValueMap.fromSingleValue(Map.of(Headers.TRANSACTION, transactionId)))),
                        String.class)
                .getBody();
    }

    private @Nullable <T> String listGroupWeb(
            final String transactionId) {
        return restTemplate.exchange(LIST_GROUP_WEB_URL, HttpMethod.GET,
                        new HttpEntity<>(HttpHeaders.readOnlyHttpHeaders(MultiValueMap.fromSingleValue(Map.of(Headers.TRANSACTION, transactionId)))),
                        String.class)
                .getBody();
    }
}
