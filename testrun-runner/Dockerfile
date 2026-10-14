# testrun-runner 이미지. 빌드 context는 저장소 루트.
#   docker build -f testrun-runner/Dockerfile --platform linux/amd64 -t testrun-runner .
# 운영 judgehost와 반드시 같은 버전·같은 아키텍처(amd64)를 쓴다 (설계 문서 8장: arm64 이미지 금지).

# jar 빌드는 빌드 머신 아키텍처에서 (에뮬레이션 없이)
FROM --platform=$BUILDPLATFORM gradle:8.10.2-jdk17 AS build
WORKDIR /src
COPY . .
RUN ./gradlew :testrun-runner:bootJar --no-daemon -q

FROM domjudge/judgehost:9.0.0
# 베이스 이미지에 JRE(21)가 들어 있다. ENTRYPOINT(dumb-init)는 그대로 쓴다.
COPY --from=build /src/testrun-runner/build/libs/testrun-runner.jar /opt/testrun/testrun-runner.jar
COPY testrun-runner/scripts/start-runner.sh /scripts/start-runner.sh
RUN chmod 755 /scripts/start-runner.sh

ENV RUNNER_SLOTS=2 \
    RUNNER_CPUS="1,2" \
    RUNNER_RUNUSER_BASE=101 \
    REDIS_HOST=redis \
    REDIS_PORT=6379 \
    DOMSERVER_BASEURL=http://domserver/ \
    HCL_INTERNAL_BASEURL=http://backend:8080/

HEALTHCHECK --interval=10s --timeout=3s --start-period=180s --retries=3 \
    CMD test -f /tmp/runner-ready && test $(( $(date +%s) - $(stat -c %Y /tmp/runner-heartbeat) )) -lt 15

CMD ["/scripts/start-runner.sh"]
