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

import com.dbpxy.config.*;
import com.dbpxy.controller.ControllerPackage;
import com.dbpxy.repository.RepositoryPackage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@EnableTransactionManagement
@EnableConfigurationProperties({
        GrpcProperties.class,
        DbpxyProperties.class,
        DbpxyDatasourceProperties.class
})
@EnableJpaRepositories(
        basePackageClasses = {
                RepositoryPackage.class,
        }
)
@SpringBootApplication(
        scanBasePackageClasses = {
                RootPackage.class,
                ConfigPackage.class,
                ControllerPackage.class,
        },
        exclude = {
                DataSourceAutoConfiguration.class,
        }
)
@Import(TestConfig.class)
public class Application {
    private final Optional<BuildProperties> buildProperties;

    public static void main(String[] args) {
        new SpringApplicationBuilder(Application.class)
                .run(args);
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        buildProperties.ifPresent(it ->
                log.info("APP_INFO=[name={}, version={}, buildTime={}]", it.getName(), it.getVersion(), it.getTime())
        );
    }
}
