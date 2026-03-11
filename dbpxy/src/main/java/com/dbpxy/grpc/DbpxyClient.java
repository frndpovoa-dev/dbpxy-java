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
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextStoppedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.function.Consumer;

@Component
public class DbpxyClient {
    private final ChannelCredentials credentials;
    private final Cache<String, ManagedChannel> channelCache = Caffeine.newBuilder()
            .expireAfter(new Expiry<String, ManagedChannel>() {
                private static final long ONE_DAY_IN_NANOS = Duration.ofDays(1).toNanos();

                public long expireAfterCreate(
                        final String node,
                        final ManagedChannel channel,
                        final long currentTime) {
                    return (channel.isShutdown() || channel.isTerminated()) ? 0 : ONE_DAY_IN_NANOS;
                }

                public long expireAfterUpdate(
                        final String node,
                        final ManagedChannel channel,
                        final long currentTime,
                        final long currentDuration) {
                    return (channel.isShutdown() || channel.isTerminated()) ? 0 : ONE_DAY_IN_NANOS;
                }

                public long expireAfterRead(
                        final String node,
                        final ManagedChannel channel,
                        final long currentTime,
                        final long currentDuration) {
                    return (channel.isShutdown() || channel.isTerminated()) ? 0 : ONE_DAY_IN_NANOS;
                }
            })
            .removalListener((final String node, final ManagedChannel channel, final RemovalCause cause) -> channel.shutdown())
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
        final ManagedChannel channel = channelCache.get(node, ignored -> Grpc
                .newChannelBuilderForAddress(node, port, credentials)
                .build());
        return (channel.isShutdown() || channel.isTerminated()) ? getChannel(node, port, retry - 1) : channel;
    }

    @EventListener(ContextStoppedEvent.class)
    public void onApplicationEvent(final ContextStoppedEvent event) {
        channelCache.invalidateAll();
    }
}
