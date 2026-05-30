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

import com.dbpxy.config.DbpxyGrpcProperties;
import com.dbpxy.service.DatabaseService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.ContextStoppedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class DbpxyServer {
    private final Server server;
    private final DatabaseService databaseService;
    private final HealthStatusManager health = new HealthStatusManager();

    public DbpxyServer(
            final DbpxyGrpcProperties dbpxyGrpcProperties,
            final DatabaseService databaseService,
            @Value("${app.dbpxy-grpc.cert-path:certs/cert.pem}") final String certPath,
            @Value("${app.dbpxy-grpc.key-path:certs/key.pem}") final String keyPath
    ) throws IOException {
        this.databaseService = databaseService;
        try (final InputStream cert = new ClassPathResource(certPath).getInputStream();
             final InputStream key = new ClassPathResource(keyPath).getInputStream()) {
            this.server = ServerBuilder
                    .forPort(dbpxyGrpcProperties.getPort())
                    .useTransportSecurity(cert, key)
                    .addService(databaseService)
                    .addService(health.getHealthService())
                    .keepAliveTime(1, TimeUnit.MINUTES)
                    .keepAliveTimeout(1, TimeUnit.SECONDS)
                    .build()
                    .start();
        }
        log.info("gRPC server started");
    }

    @EventListener(AvailabilityChangeEvent.class)
    public void onReadinessStateChange(
            final AvailabilityChangeEvent<ReadinessState> event) throws InterruptedException {
        if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
            health.setStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, HealthCheckResponse.ServingStatus.SERVING);
            log.info("gRPC server listening on port {}", server.getPort());
        } else if (event.getState() == ReadinessState.REFUSING_TRAFFIC) {
            log.info("gRPC server graceful shutdown...");
            health.setStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, HealthCheckResponse.ServingStatus.NOT_SERVING);
        }
    }

    @PreDestroy
    public void onShutdown() {
        try {
            log.info("stopping gRPC server...");
            if (server.shutdown().awaitTermination(15, TimeUnit.SECONDS)) {
                log.info("gRPC server stopped");
            } else {
                log.warn("forcing gRPC shutdown...");
                server.shutdownNow();
            }
        } catch (final InterruptedException e) {
            log.error("shutdown interrupted, forcing gRPC shutdown...", e);
            server.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            databaseService.onShutdown();
        }
    }

    @EventListener(ContextStoppedEvent.class)
    public void onContextStoppedEvent(final ContextStoppedEvent event) {
        onShutdown();
    }
}