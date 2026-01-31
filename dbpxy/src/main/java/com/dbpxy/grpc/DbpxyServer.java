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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class DbpxyServer implements InitializingBean, DisposableBean {
    private static Server server;
    private final GrpcProperties grpcProperties;
    private final DatabaseService databaseService;
    @Value("${dbpxy.grpc-cert-path:certs/cert.pem}")
    private String certPath;
    @Value("${dbpxy.grpc-key-path:certs/key.pem}")
    private String keyPath;

    @Override
    public void destroy() throws Exception {
        try {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdownNow().awaitTermination(30, TimeUnit.SECONDS);
        } finally {
            server = null;
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (server == null || server.isShutdown()) {
            try (final InputStream cert = new ClassPathResource(certPath).getInputStream();
                 final InputStream key = new ClassPathResource(keyPath).getInputStream()) {
                server = ServerBuilder
                        .forPort(grpcProperties.getPort())
                        .useTransportSecurity(cert, key)
                        .addService(databaseService)
                        .build()
                        .start();
            }
        }
    }
}
