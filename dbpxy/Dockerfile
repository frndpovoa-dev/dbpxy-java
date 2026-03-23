ARG VERSION

FROM alpine:3.23 AS builder
WORKDIR /staging
RUN true \
    && apk add --no-cache curl ca-certificates \
    && curl -L https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/v0.4.47/grpc_health_probe-linux-amd64 -o grpc_health_probe

FROM dbpxy:${VERSION}
COPY --from=builder --chmod=755 /staging/grpc_health_probe /workspace/grpc_health_probe
