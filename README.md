# dbpxy

### "Caminha e o caminho se abrirá", Gassho.

[See the original LinkedIn post here.](https://www.linkedin.com/posts/activity-7343623220677201920-6qk9?utm_source=share&utm_medium=member_desktop&rcm=ACoAABC2-aoB9oRA7fI-ca2qc4EhypSjGLhoDaE)

I'd like to share a project I've been working on in past couple of years. In simple words: it's an implementation of shared database transactions for architectures based on microservices.

It can be used to try and solve problem scenarios such as the ones below:

1. Spring Boot + JPA => Begin Tx via @Transactional => Read/Write locally, Read/Write API in same Tx => Commit/Rollback Tx.
2. Begin Tx via gRPC => Read API1, Read API2, Write API1, Read API1, Read API2 => Commit/Rollback Tx via gRPC.

Other than those scenarios above, it might be helpful to:

1. Modernize monolith systems by function while keeping existing centralized RDBMS.
2. Share DB transactions on pure microservice/function-based architectures.
3. Store weights from dense neural networks into external RDBMS.
4. Run parallel hyperparameter tuning epics in different transactions, given (3) is feasible.
5. Share DB transactions on AI code agent systems.

Have a good day!

![image](https://github.com/user-attachments/assets/5f279bae-743f-4ac8-8bc6-275fc34d3a5b)

## Build

[![Build from a feature branch](https://github.com/frndpovoa-dev/dbpxy-java/actions/workflows/build.yaml/badge.svg?branch=feature%2F0.4.0)](https://github.com/frndpovoa-dev/dbpxy-java/actions/workflows/build.yaml)

```bash
true \
  && ./mvnw clean install
```

## Publish

Don't forget to change your version number.

```bash
true \
  && source .env \
  && ./publish.sh -v "0.0.0-0-SNAPSHOT" \
    -a $GCP_SVC_ACCOUNT \
    -p $GCP_PROJECT_ID \
    -r $GCP_REGION \
    -m $MAVEN_REPOSITORY \
    -d $DOCKER_REPOSITORY
```
