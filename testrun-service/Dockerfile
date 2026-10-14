# testrun-service 이미지. 빌드 context는 저장소 루트.
#   docker build -f testrun-service/Dockerfile -t testrun-service .
FROM --platform=$BUILDPLATFORM gradle:8.10.2-jdk17 AS build
WORKDIR /src
COPY . .
RUN ./gradlew :testrun-service:bootJar --no-daemon -q

FROM eclipse-temurin:17-jre
WORKDIR /opt/app
COPY --from=build /src/testrun-service/build/libs/testrun-service.jar app.jar
RUN groupadd -r app && useradd -r -g app app && chown -R app:app /opt/app
USER app
EXPOSE 8090
# curl/wget 없이 bash /dev/tcp로 actuator health 확인
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8090 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q "\"UP\"" <&3'
CMD ["sh", "-c", "exec java ${JAVA_OPTS:--Xmx256m} -jar /opt/app/app.jar"]
