package com.dbpxy.config;

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

import com.dbpxy.ConnectionHolder;
import com.dbpxy.grpc.GrpcServer;
import com.dbpxy.jdbc.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.Environment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

public class TestConfig {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    @Primary
    public ConnectionHolder connectionHolder() {
        return new ConnectionHolder();
    }

    @Bean("dataSource")
    @Primary
    public DataSource dataSource(
            final ConnectionHolder connectionHolder,
            final DbpxyProperties dbpxyProperties,
            final DbpxyDatasourceProperties dbpxyDatasourceProperties,
            @Value("${app.grpc.cert-path:certs/cert.pem}") final String dbpxyCertPath
    ) {
        return new DataSource(
                connectionHolder,
                dbpxyDatasourceProperties,
                dbpxyProperties,
                dbpxyCertPath);
    }

    @Bean("entityManagerFactory")
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            final DataSource dataSource,
            final GrpcServer grpcServer // depends-on
    ) {
        final HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setShowSql(true);
        vendorAdapter.setGenerateDdl(true);

        final Map<String, Object> jpaProperties = new HashMap<>();
        jpaProperties.put(Environment.HBM2DDL_AUTO, "create-drop");
        jpaProperties.put(Environment.DIALECT, "org.hibernate.dialect.PostgreSQLDialect");
        jpaProperties.put(Environment.SHOW_SQL, true);
        jpaProperties.put(Environment.FORMAT_SQL, true);

        final LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan(com.dbpxy.bo.Package.class.getPackageName());
        em.setJpaVendorAdapter(vendorAdapter);
        em.setJpaPropertyMap(jpaProperties);
        em.afterPropertiesSet();
        return em;
    }

    @Bean("transactionManager")
    @Primary
    public JpaTransactionManager transactionManager(
            final EntityManagerFactory entityManagerFactory,
            final DataSource dataSource
    ) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setDataSource(dataSource);
        transactionManager.setEntityManagerFactory(entityManagerFactory);
        transactionManager.afterPropertiesSet();
        return transactionManager;
    }
}
