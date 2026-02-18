package com.dbpxy.grpc;

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

import com.dbpxy.config.GrpcProperties;
import com.dbpxy.service.DatabaseService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextStoppedEvent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Component
public class DbpxyServer implements ApplicationListener<ContextStoppedEvent> {
    private final Server server;

    public DbpxyServer(
            final GrpcProperties grpcProperties,
            final DatabaseService databaseService,
            @Value("${dbpxy.grpc-cert-path:certs/cert.pem}") final String certPath,
            @Value("${dbpxy.grpc-key-path:certs/key.pem}") final String keyPath
    ) throws IOException {
        try (final InputStream cert = new ClassPathResource(certPath).getInputStream();
             final InputStream key = new ClassPathResource(keyPath).getInputStream()) {
            this.server = ServerBuilder
                    .forPort(grpcProperties.getPort())
                    .useTransportSecurity(cert, key)
                    .addService(databaseService)
                    .build()
                    .start();
            log.info("gRPC server started on port: {}", grpcProperties.getPort());
        }
    }

    @Override
    public void onApplicationEvent(final ContextStoppedEvent event) {
        try {
            server.shutdownNow().awaitTermination();
            log.info("gRPC server stopped");
        } catch (final InterruptedException e) {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    public boolean supportsAsyncExecution() {
        return false;
    }
}
