# dbpxy

## Build & dockerize

Don't forget to change version number.

```bash
true \
  && source .env \
  && ./release.sh -v "0.1.0-26-SNAPSHOT" -i $DOCKER_IMAGE
```

Generate SSL certificate
```bash
openssl req -x509 -newkey rsa:4096 -nodes -keyout key.pem -out cert.pem -days 3650 -config localhost.cnf
```
