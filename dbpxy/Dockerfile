FROM dhi.io/amazoncorretto:25
USER 65532
WORKDIR /appbin
COPY target/*-app.jar app.jar
HEALTHCHECK NONE
ENTRYPOINT ["java"]
CMD ["-jar", "app.jar"]
