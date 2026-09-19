package com.dbpxy.config;

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


import com.dbpxy.ConnectionHolder;
import com.dbpxy.jdbc.DataSource;
import com.dbpxy.springframework.TransactionExecutionListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

@Slf4j
@AutoConfiguration(
        before = {
                DataSourceAutoConfiguration.class,
                DataSourceInitializationAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                TransactionAutoConfiguration.class,
        },
        after = {
                TransactionManagerCustomizationAutoConfiguration.class,
        }
)
@ConditionalOnProperties({
        @ConditionalOnProperty(prefix = "app.dbpxy", name = "hostname"),
        @ConditionalOnProperty(prefix = "app.dbpxy", name = "port")
})
public class DbpxyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ConnectionHolder.class)
    public ConnectionHolder connectionHolder(
            final ObjectProvider<DataSource> dataSourceProvider) {
        return new ConnectionHolder(dataSourceProvider);
    }

    @Bean(name = "dataSource")
    public DataSource dataSource(
            final DbpxyProperties dbpxyProperties,
            final Optional<DbpxyDatasourceProperties> maybeDbpxyDatasourceProperties,
            @Value("${app.grpc.grpc-cert-path:certs/cert.pem}") final String dbpxyCertPath
    ) {
        return new DataSource(
                maybeDbpxyDatasourceProperties,
                dbpxyProperties,
                dbpxyCertPath);
    }


    @Bean
    @ConditionalOnMissingBean(PlatformTransactionManager.class)
    public PlatformTransactionManager transactionManager(
            final ConnectionHolder connectionHolder,
            final DataSource dataSource
    ) {
        final JdbcTransactionManager transactionManager = new JdbcTransactionManager();
        transactionManager.setDataSource(dataSource);
        transactionManager.setTransactionExecutionListeners(List.of(
                TransactionExecutionListener.builder()
                        .dataSource(dataSource)
                        .connectionHolder(connectionHolder)
                        .build()));
        transactionManager.afterPropertiesSet();
        return transactionManager;
    }

    @Configuration
    @ConditionalOnProperty(prefix = "app.dbpxy-datasource", name = "url")
    @EnableConfigurationProperties({
            DbpxyProperties.class,
            DbpxyDatasourceProperties.class,
    })
    public static class DbpxyServerAndDatasourceConfiguration {
    }

    @Configuration
    @ConditionalOnMissingBean(DbpxyServerAndDatasourceConfiguration.class)
    @EnableConfigurationProperties({
            DbpxyProperties.class,
    })
    public static class DbpxyServerOnlyConfiguration {
    }
}

