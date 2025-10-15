# dbpxy

Generate SSL certificate
```bash
openssl req -x509 -newkey rsa:4096 -nodes -keyout key.pem -out cert.pem -days 3650 -config localhost.cnf
```
