package com.dbpxy;

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

import com.dbpxy.hint.CaffeineRuntimeHints;
import com.dbpxy.hint.DbpxyRuntimeHints;
import com.dbpxy.hint.LogbackRuntimeHints;
import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.event.EventListener;

import java.util.Optional;
import java.util.TimeZone;

@Slf4j
@RequiredArgsConstructor
@ImportRuntimeHints(value = {
        CaffeineRuntimeHints.class,
        DbpxyRuntimeHints.class,
        LogbackRuntimeHints.class,
})
@SpringBootApplication(scanBasePackageClasses = {Package.class})
@ConfigurationPropertiesScan(basePackageClasses = {Package.class})
public class Application implements CommandLineRunner {
    private final Optional<BuildProperties> buildProperties;
    private final Object lock = new Object();
    private boolean shouldContinueRunning = true;

    static void main(final String[] args) {
        new SpringApplicationBuilder(Application.class)
                .run(args);
    }

    @Override
    public void run(@Nullable final String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        log.info("default timezone set to UTC");
        synchronized (lock) {
            while (shouldContinueRunning) {
                try {
                    log.info("dbpxy is running");
                    lock.wait();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        log.info("dbpxy stopped");
    }

    @PreDestroy
    public void onShutdown() {
        synchronized (lock) {
            log.info("stopping dbpxy...");
            shouldContinueRunning = false;
            lock.notifyAll();
        }
    }

    @EventListener
    public void onReady(final ApplicationReadyEvent event) {
        buildProperties.ifPresent(app ->
                log.info("dbpxy,n={},v={},t={}",
                        app.getName(),
                        app.getVersion(),
                        app.getTime())
        );
    }
}
