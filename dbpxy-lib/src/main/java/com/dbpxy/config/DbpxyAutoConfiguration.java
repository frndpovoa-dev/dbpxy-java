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
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.SchemaToolingSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.Database;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
@EnableConfigurationProperties({
        DbpxyProperties.class,
        DbpxyDatasourceProperties.class
})
public class DbpxyAutoConfiguration {
    @Bean
    public ConnectionHolder connectionHolder() {
        return new ConnectionHolder();
    }

    @Bean(name = "dataSource")
    public DataSource dataSource(
            final ConnectionHolder connectionHolder,
            final DbpxyProperties dbpxyProperties,
            final DbpxyDatasourceProperties dbpxyDatasourceProperties,
            @Value("${app.grpc.grpc-cert-path:certs/cert.pem}") final String dbpxyCertPath
    ) {
        return new DataSource(
                connectionHolder,
                dbpxyDatasourceProperties,
                dbpxyProperties,
                dbpxyCertPath);
    }

    @Bean
    @ConditionalOnMissingBean(name = {"dbpxyServer"})
    public String dbpxyServer() {
        return "dbpxyServer-not-available-in-dbpxy-lib";
    }

    @Bean
    @DependsOn({"dbpxyServer"})
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            final DataSource dataSource,
            final ApplicationContext context,
            @Value("${app.dbpxy.ddl-auto:none}") final String ddlAuto,
            @Value("${app.dbpxy.show-sql:false}") final boolean showSql,
            @Value("${app.dbpxy.format-sql:false}") final boolean formatSql
    ) {
        final HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setDatabase(Database.POSTGRESQL);

        final Map<String, Object> jpaProperties = new HashMap<>();
        jpaProperties.put(SchemaToolingSettings.HBM2DDL_AUTO, ddlAuto);
        jpaProperties.put(JdbcSettings.SHOW_SQL, showSql);
        jpaProperties.put(JdbcSettings.FORMAT_SQL, formatSql);

        final LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan(EntityScanPackages.get(context).getPackageNames().toArray(String[]::new));
        em.setJpaVendorAdapter(vendorAdapter);
        em.setJpaPropertyMap(jpaProperties);
        em.afterPropertiesSet();
        return em;
    }

    @Bean
    public JpaTransactionManager transactionManager(
            final EntityManagerFactory entityManagerFactory,
            final ConnectionHolder connectionHolder,
            final DataSource dataSource
    ) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setDataSource(dataSource);
        transactionManager.setEntityManagerFactory(entityManagerFactory);
        transactionManager.setTransactionExecutionListeners(List.of(
                TransactionExecutionListener.builder()
                        .entityManagerFactory(entityManagerFactory)
                        .connectionHolder(connectionHolder)
                        .build()));
        transactionManager.afterPropertiesSet();
        return transactionManager;
    }
}

