[![Build from a feature branch](https://github.com/frndpovoa-dev/dbpxy-java/actions/workflows/build.yaml/badge.svg?branch=feature%2F0.9)](https://github.com/frndpovoa-dev/dbpxy-java/actions/workflows/build.yaml)

# DBPXY - Shareable Database Transactions for Microservices

### "Caminha e o caminho se abrirá", Gassho.

[See the original LinkedIn post here.](https://www.linkedin.com/posts/activity-7343623220677201920-6qk9?utm_source=share&utm_medium=member_desktop&rcm=ACoAABC2-aoB9oRA7fI-ca2qc4EhypSjGLhoDaE)

I'd like to share a project I've been working on in past couple of years. In simple words: it's an implementation of shareable database transactions for architectures based on microservices.

It can be used to try and solve problem scenarios such as the following:

1. Using Spring Boot and JPA do begin transaction using @Transactional, then read/write local repository, then read/write APIs 1..N in same transaction, then commit/rollback.
2. Using gRPC do begin transaction, then read/write APIs 1..N in same transaction, then commit/rollback.

Other than those scenarios above, it might be helpful to:

1. Modernize monolith systems by function while keeping existing centralized RDBMS.
2. Share DB transactions on pure microservice/function-based architectures.
3. Store weights from dense neural networks into external RDBMS.
4. Run parallel hyperparameter tuning epics in different transactions, given (3) is feasible.
5. Share DB transactions on AI code agent systems.

Have a good day!

![image](https://github.com/user-attachments/assets/5f279bae-743f-4ac8-8bc6-275fc34d3a5b)

## Quick build

* Docker environment is required to build server image and alternatives like Podman also work well.
* Testing is optional and can be skipped using `-Dmaven.test.skip=true`.
* GPG signing is optional and can be skipped using `-Dgpg.skip=true`.

```bash
true \
  && mvn clean install \
    spring-boot:build-image \
    -Drevision=0.0.0-0-SNAPSHOT \
    -Dmaven.test.skip=true \
    -Dgpg.skip=true
```

### How to use

For Java applications, add below Maven dependency.

```xml
<dependency>
  <groupId>com.dbpxy</groupId>
  <artifactId>dbpxy-lib</artifactId>
  <version>0.0.0-0-SNAPSHOT</version>
</dependency>
```

Then, point your client application to your RDBMS via the DBPXY server container.

For Spring Boot applications, add below properties and replace/remove default values.
Data source and transaction manager will be autoconfigured as part of [DbpxyAutoConfiguration.java](dbpxy-lib/src/main/java/com/dbpxy/config/DbpxyAutoConfiguration.java).

PostgreSQL driver and Cloud SQL for PostgreSQL connector are available in default Docker image.

```yaml
app:
  dbpxy:
    hostname: ${DB_PROXY_HOST:localhost}
    port: ${DB_PROXY_PORT:9090}
    keep-alive-interval-in-ms: 30000
    keep-alive-timeout-in-ms: 10000
  dbpxy-datasource:
    activation: LAZY
    database: POSTGRESQL
    url: ${DB_URL:jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_DATABASE:postgres}}
    props:
      - name: user
        value: ${DB_USER:postgres}
      - name: password
        value: ${DB_PASSWORD:postgres}
```

Then, run DBPXY container.

```bash
true \
  && docker run --rm \
    -v ./certs/cert.pem:/workspace/BOOT-INF/classes/certs/cert.pem \
    -v ./certs/key.pem:/workspace/BOOT-INF/classes/certs/key.pem \
    -p 9090:9090 \
    dbpxy-server:0.0.0-0-SNAPSHOT
```

Docker image is also available on [Docker Hub](https://hub.docker.com/r/dbpxy/dbpxy-server).
