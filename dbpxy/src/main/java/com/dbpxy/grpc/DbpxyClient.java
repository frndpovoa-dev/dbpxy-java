package com.dbpxy.grpc;

/*-
 * #%L
 * dbpxy
 * $Id:$
 * $HeadURL:$
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

import com.dbpxy.config.GrpcProperties;
import com.dbpxy.proto.DbpxyGrpc;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.function.Consumer;

@Component
public class DbpxyClient {
    private final GrpcProperties grpcProperties;
    private final ChannelCredentials credentials;
    private final Cache<String, ManagedChannel> channelMap = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofDays(1))
            .removalListener((final String node, final ManagedChannel channel, final RemovalCause cause) -> channel.shutdown())
            .build();

    public DbpxyClient(
            final GrpcProperties grpcProperties,
            @Value("${app.grpc.grpc-cert-path:certs/cert.pem}") final String certPath
    ) throws IOException {
        this.grpcProperties = grpcProperties;
        try (final InputStream cert = new ClassPathResource(certPath).getInputStream()) {
            this.credentials = TlsChannelCredentials.newBuilder()
                    .trustManager(cert)
                    .build();
        }
    }

    public void invoke(
            final String node,
            final Consumer<DbpxyGrpc.DbpxyBlockingStub> callback) {
        final ManagedChannel channel = channelMap.get(node, ignored -> Grpc
                .newChannelBuilderForAddress(node, grpcProperties.getPort(), credentials)
                .build());
        final DbpxyGrpc.DbpxyBlockingStub blockingStub = DbpxyGrpc.newBlockingStub(channel);
        callback.accept(blockingStub);
    }
}
