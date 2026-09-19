[![GitHub](https://img.shields.io/github/actions/workflow/status/frndpovoa-dev/dbpxy-java/build.yaml?branch=feature%2F0.10&logo=github&logoColor=white&label=Build&color=green)](https://github.com/frndpovoa-dev/dbpxy-java/actions/workflows/build.yaml)
[![Maven](https://img.shields.io/maven-central/v/com.dbpxy/dbpxy-lib?label=Maven&color=blue)](https://repo1.maven.org/maven2/com/dbpxy/)
[![Docker](https://img.shields.io/docker/v/dbpxy/dbpxy-server?logo=docker&logoColor=white&label=Docker&color=blue)](https://hub.docker.com/r/dbpxy/dbpxy-server)
![License](https://img.shields.io/github/license/frndpovoa-dev/dbpxy-java?label=License&color=orange)
![scarf.sh pixel](https://static.scarf.sh/a.png?x-pxid=064ab4cf-e1ee-47b6-bfc1-4d0b82b96a15)

# DBPXY - Shareable Database Transactions for Microservices

### "Caminha e o caminho se abrirá", Gassho.

[See the original LinkedIn post here](https://www.linkedin.com/posts/activity-7343623220677201920-6qk9?utm_source=share&utm_medium=member_desktop&rcm=ACoAABC2-aoB9oRA7fI-ca2qc4EhypSjGLhoDaE).

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

## Quick build for local development

Build this Maven project from root folder using command-line below.

* Docker environment is required to build server image and alternatives like Podman also work well.
* Testing is optional and can be skipped using `-Dmaven.test.skip=true`.
* GPG signing is optional and can be skipped using `-Dgpg.skip=true`.
* Version in `revision` argument might need to be adjusted according to your release cycle.

```bash
true \
  && mvn clean install \
    spring-boot:build-image \
    -Drevision=0.0.0.0-0-SNAPSHOT \
    -Dmaven.test.skip=false \
    -Dgpg.skip=true
```

### How to use

For your Java client applications, start by adding the following Maven dependency.

* Adjust artifact `version` to be same as `revision` from previous step. 

```xml
<dependency>
    <groupId>com.dbpxy</groupId>
    <artifactId>dbpxy-lib</artifactId>
    <version>0.0.0.0-0-SNAPSHOT</version>
</dependency>
```

Then, connect your client application to your RDBMS through the DBPXY server.
For Spring Boot applications, add these properties and replace/remove default values.

* Data source and transaction manager beans can be autoconfigured as part of [DbpxyAutoConfiguration.java](dbpxy-lib/src/main/java/com/dbpxy/config/DbpxyAutoConfiguration.java).
* PostgreSQL driver and Cloud SQL for PostgreSQL connector are available in default Docker image.

```yaml
app:
  dbpxy:
    hostname: ${DBPXY_HOST:dbpxy}
    port: ${DBPXY_PORT:9090}
    keep-alive-interval-in-ms: 30000
    keep-alive-timeout-in-ms: 10000
  dbpxy-datasource:
    activation: LAZY
    database: POSTGRESQL
    url: ${DB_URL:jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_DATABASE:postgres}}
    props:
      - name: user
        value: ${DB_USERNAME:postgres}
      - name: password
        value: ${DB_PASSWORD:postgres}
```

Next, generate a private key and share the certificate to your client applications.

* Certificate is required to secure gRPC communications between your client applications and DBPXY server.
* Adjust your [configuration template](dbpxy-server/src/main/resources/certs/localhost.cnf-template) and certificate validity as needed.

```bash
true \
  && CERTS_DIR=certs \
  && openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
    -keyout $CERTS_DIR/key.pem \
    -out $CERTS_DIR/cert.pem \
    -config $CERTS_DIR/localhost.cnf-template
```

Then, run one or more DBPXY server containers.

* Name and hostname are required, if not provided by container orchestrator.
* Network can be used to isolate your applications from other applications in same container runtime.
* Network alias can be used as DNS-based load balancer, if not provided by container orchestrator.
* Adjust image `version` to be same as `revision` from previous step.

```bash
true \
  && docker run --rm -it \
    --name dbpxy-1 \
    --hostname dbpxy-1 \
    --network development \
    --network-alias dbpxy \
    --volume ./certs/cert.pem:/workspace/BOOT-INF/classes/certs/cert.pem \
    --volume ./certs/key.pem:/workspace/BOOT-INF/classes/certs/key.pem \
    -p 9090:9090 \
    dbpxy-server:0.0.0.0-0-SNAPSHOT
```

## Downloadable artifacts and images

Pre-built Maven artifacts and Docker images can be found on:

* [Docker Hub](https://hub.docker.com/r/dbpxy/dbpxy-server)
* [Maven Central Repository](https://repo1.maven.org/maven2/com/dbpxy/)

## Versioning

We version all our releases using the format `0.0.0.0-0` which means `MAJOR.MINOR.PATCH.HOTFIX-RETRY`.

## License

Copyright 2025 Fernando Lemes Povoa. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
these files except in compliance with the License. You may obtain a copy of the
License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
