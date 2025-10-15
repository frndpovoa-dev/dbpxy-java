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
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GrpcServer implements InitializingBean, DisposableBean {
    private static Server server;
    private final GrpcProperties grpcProperties;
    private final DatabaseService databaseService;

    @Override
    public void destroy() throws Exception {
        server.shutdownNow().awaitTermination();
        server = null;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (server == null || server.isShutdown()) {
            server = ServerBuilder
                    .forPort(grpcProperties.getPort())
                    .useTransportSecurity(
                            new ClassPathResource("certs/cert.pem").getInputStream(),
                            new ClassPathResource("certs/key.pem").getInputStream())
                    .addService(databaseService)
                    .build()
                    .start();
        }
    }
}
