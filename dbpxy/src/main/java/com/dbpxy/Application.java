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


import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;

import java.util.Optional;
import java.util.TimeZone;

@Slf4j
@RequiredArgsConstructor
@SpringBootApplication(scanBasePackages = {"com.dbpxy"})
@ConfigurationPropertiesScan(basePackages = {"com.dbpxy"})
public class Application {
    private final Optional<BuildProperties> buildProperties;

    static void main(final String[] args) {
        new SpringApplicationBuilder(Application.class)
                .run(args);
    }

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @EventListener
    public void onReady(final ApplicationReadyEvent event) {
        buildProperties.ifPresent(app ->
                log.info("app_info,{},v={},t={}",
                        app.getName(),
                        app.getVersion(),
                        app.getTime())
        );
    }
}
