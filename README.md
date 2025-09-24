# dbpxy

## "Caminha e o caminho se abrirá", Gassho. 

![image](https://github.com/user-attachments/assets/5f279bae-743f-4ac8-8bc6-275fc34d3a5b)

## Build & dockerize

Don't forget to change version number.

```bash
true \
  && source .env \
  && ./release.sh -v "0.0.0-0-SNAPSHOT" \
    -p $GCP_PROJECT_ID \
    -r $GCP_REGION \
    -m $MAVEN_REPOSITORY \
    -d $DOCKER_REPOSITORY \
    -i $DOCKER_IMAGE
```
