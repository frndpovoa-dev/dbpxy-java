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
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class DbpxyClient implements InitializingBean {
    private final GrpcProperties grpcProperties;
    private ChannelCredentials credentials;

    @Override
    public void afterPropertiesSet() throws Exception {
        try (final InputStream cert = new ClassPathResource("certs/cert.pem").getInputStream()) {
            this.credentials = TlsChannelCredentials.newBuilder()
                    .trustManager(cert)
                    .build();
        }
    }

    public void invoke(
            final String node,
            final Consumer<DbpxyGrpc.DbpxyBlockingStub> callback) throws SQLException {
        ManagedChannel channel = null;
        try {
            channel = Grpc.newChannelBuilderForAddress(
                            node,
                            grpcProperties.getPort(),
                            credentials)
                    .build();
            final DbpxyGrpc.DbpxyBlockingStub blockingStub = DbpxyGrpc.newBlockingStub(channel);
            callback.accept(blockingStub);
        } finally {
            if (channel != null) {
                try {
                    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException(e);
                }
            }
        }
    }
}
