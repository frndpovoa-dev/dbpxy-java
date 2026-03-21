FROM dhi.io/amazoncorretto:25-alpine3.23-dev AS builder
WORKDIR /staging
RUN true \
    && apk add --no-cache curl ca-certificates \
    && curl -L https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/v0.4.47/grpc_health_probe-linux-amd64 -o grpc_health_probe \
    && chmod +x grpc_health_probe \

FROM dhi.io/amazoncorretto:25-alpine3.23
USER 65532
WORKDIR /appbin
COPY --from=builder --chmod=644 /staging/grpc_health_probe grpc_health_probe
COPY target/*-app.jar app.jar
HEALTHCHECK NONE
ENTRYPOINT ["java"]
CMD ["-jar", "app.jar"]
