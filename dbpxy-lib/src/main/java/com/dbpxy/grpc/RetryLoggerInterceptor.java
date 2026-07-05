package com.dbpxy.grpc;

/*-
 * #%L
 * dbpxy-lib
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

import io.grpc.*;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class RetryLoggerInterceptor implements ClientInterceptor {

    @Override
    public <REQ, RES> ClientCall<REQ, RES> interceptCall(
            final MethodDescriptor<REQ, RES> method,
            final CallOptions callOptions,
            final Channel next) {

        final AtomicInteger attemptCounter = new AtomicInteger(0);

        final ClientStreamTracer.Factory tracerFactory = new ClientStreamTracer.Factory() {
            @Override
            public ClientStreamTracer newClientStreamTracer(
                    final ClientStreamTracer.StreamInfo info,
                    final Metadata headers) {

                final int currentAttempt = attemptCounter.incrementAndGet();

                if (currentAttempt > 1) {
                    log.warn("retrying gRPC call {}, attempt {}", method.getFullMethodName(), currentAttempt);
                } else {
                    log.debug("initiating gRPC call {}", method.getFullMethodName());
                }

                return new ClientStreamTracer() {
                    @Override
                    public void streamClosed(final Status status) {
                        if (!status.isOk() && currentAttempt > 1) {
                            log.error("retry attempt {} failed with status: {} ({})", currentAttempt, status.getCode(), status.getDescription());
                        }
                    }
                };
            }
        };

        return next.newCall(method, callOptions.withStreamTracerFactory(tracerFactory));
    }
}
