# syntax=docker/dockerfile:1.7
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
# Resolve dependencies in a cacheable layer. The source tree is copied only afterwards.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package \
    && test -s target/gitcode-inbox.jar

FROM eclipse-temurin:21-jre-jammy
ARG APP_VERSION=dev
LABEL org.opencontainers.image.title="gitcode-inbox" \
      org.opencontainers.image.version="$APP_VERSION" \
      org.opencontainers.image.source="https://github.com/KaranocaVe/gitcode-inbox"
RUN useradd --system --uid 10001 --create-home --home-dir /home/app app \
    && install --directory --owner=app --group=app /app /data
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/gitcode-inbox.jar /app/gitcode-inbox.jar
ENV INBOX_MODE=production \
    INBOX_DB=/data/inbox.sqlite \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["/bin/bash", "-ec", "exec 3<>/dev/tcp/127.0.0.1/8080; printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3; read -r -t 3 protocol status rest <&3; test \"$status\" = 200"]
ENTRYPOINT ["java", "-jar", "/app/gitcode-inbox.jar"]
