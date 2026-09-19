package com.dbpxy.config;

/*-
 * #%L
 * dbpxy-server
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.SchemaToolingSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.Database;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class JpaConfig {

    @Bean
    @Primary
    public ConnectionHolder connectionHolder(
            final ObjectProvider<DataSource> dataSource,
            final ObjectProvider<EntityManager> entityManagerObjectFactory) {
        return new ConnectionHolder(dataSource) {
            @Override
            protected void entityManagerFlush() {
                entityManagerObjectFactory.getObject().flush();
            }

            @Override
            protected void entityManagerClear() {
                entityManagerObjectFactory.getObject().clear();
            }
        };
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
            final Optional<DbpxyDatasourceProperties> maybeDbpxyDatasourceProperties,
            @Value("${app.dbpxy.ddl-auto:none}") final String ddlAuto,
            @Value("${app.dbpxy.show-sql:false}") final boolean showSql,
            @Value("${app.dbpxy.format-sql:false}") final boolean formatSql
    ) {
        final HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setDatabase(Database.valueOf(maybeDbpxyDatasourceProperties
                .map(DbpxyDatasourceProperties::getDatabase)
                .orElse(DbpxyDatasourceProperties.Database.H2)
                .name()));

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
    @Primary
    public PlatformTransactionManager transactionManager(
            final EntityManagerFactory entityManagerFactory,
            final ConnectionHolder connectionHolder,
            final DataSource dataSource
    ) {
        final JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setDataSource(dataSource);
        transactionManager.setEntityManagerFactory(entityManagerFactory);
        transactionManager.setTransactionExecutionListeners(List.of(
                TransactionExecutionListener.builder()
                        .dataSource(dataSource)
                        .connectionHolder(connectionHolder)
                        .build()));
        transactionManager.afterPropertiesSet();
        return transactionManager;
    }
}
