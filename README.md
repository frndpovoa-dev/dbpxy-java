# dbpxy

## "Caminha e o caminho se abrirá", Gassho. 

![image](https://github.com/user-attachments/assets/5f279bae-743f-4ac8-8bc6-275fc34d3a5b)

## Build

[![Build from a feature branch](https://github.com/frndpovoa-dev/dbpxy-java/actions/workflows/build.yaml/badge.svg?branch=feature%2F0.3.0)](https://github.com/frndpovoa-dev/dbpxy-java/actions/workflows/build.yaml)

```bash
true \
  && ./mvnw clean install
```

## Publish

Don't forget to change version number.

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
