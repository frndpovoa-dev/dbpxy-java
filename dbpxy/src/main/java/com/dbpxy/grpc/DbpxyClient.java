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

import com.dbpxy.proto.DbpxyGrpc;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextStoppedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.function.Consumer;

@Slf4j
@Component
public class DbpxyClient {
    private final ChannelCredentials credentials;
    private final Cache<String, ManagedChannel> channelCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofDays(1))
            .removalListener((final String key, final ManagedChannel channel, final RemovalCause cause) -> channel.shutdown())
            .build();

    public DbpxyClient(
            @Value("${app.grpc.grpc-cert-path:certs/cert.pem}") final String certPath
    ) throws IOException {
        try (final InputStream cert = new ClassPathResource(certPath).getInputStream()) {
            this.credentials = TlsChannelCredentials.newBuilder()
                    .trustManager(cert)
                    .build();
        }
    }

    public void invoke(
            final String node,
            final int port,
            final Consumer<DbpxyGrpc.DbpxyBlockingStub> callback) {
        final ManagedChannel channel = getChannel(node, port, 3);
        final DbpxyGrpc.DbpxyBlockingStub blockingStub = DbpxyGrpc.newBlockingStub(channel);
        callback.accept(blockingStub);
    }

    private ManagedChannel getChannel(
            final String node,
            final int port,
            final int retry) {
        if (retry <= 0) {
            throw new RuntimeException("Exhausted retries trying to create gRPC channel to " + node + ":" + port + ".");
        }
        final String cacheKey = node + ":" + port;
        final ManagedChannel channel = channelCache.get(cacheKey, ignored -> {
            log.debug("creating gRPC channel to {}:{} attempts remaining {}", node, port, retry);
            return Grpc.newChannelBuilderForAddress(node, port, credentials).build();
        });
        if (channel.isShutdown() || channel.isTerminated()) {
            channelCache.asMap().remove(cacheKey, channel);
            log.debug("recreating gRPC channel to {}:{}", node, port);
            return getChannel(node, port, retry - 1);
        }
        return channel;
    }

    @EventListener(ContextStoppedEvent.class)
    public void onApplicationEvent(final ContextStoppedEvent event) {
        channelCache.invalidateAll();
    }
}
