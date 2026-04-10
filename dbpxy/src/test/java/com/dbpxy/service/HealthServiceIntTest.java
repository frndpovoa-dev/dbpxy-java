package com.dbpxy.service;

/*-
 * #%L
 * dbpxy
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

import com.dbpxy.BaseIntTest;
import com.dbpxy.config.DbpxyGrpcProperties;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.protobuf.services.HealthStatusManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class HealthServiceIntTest extends BaseIntTest {
    @Autowired
    private DbpxyGrpcProperties dbpxyGrpcProperties;

    @Test
    void ping() throws Exception {
        final ChannelCredentials credentials = TlsChannelCredentials.newBuilder()
                .trustManager(new ClassPathResource("certs/cert.pem").getInputStream())
                .build();
        final ManagedChannel channel = Grpc.newChannelBuilderForAddress(
                        "localhost",
                        dbpxyGrpcProperties.getPort(),
                        credentials)
                .build();

        try {
            final HealthGrpc.HealthBlockingStub blockingStub = HealthGrpc.newBlockingStub(channel);
            final HealthCheckResponse response = blockingStub.check(HealthCheckRequest.newBuilder()
                    .setService(HealthStatusManager.SERVICE_NAME_ALL_SERVICES)
                    .build());

            assertThat(response)
                    .isNotNull()
                    .extracting("status")
                    .isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
        } finally {
            channel.shutdownNow();
        }
    }
}
