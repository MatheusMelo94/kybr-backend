# syntax=docker/dockerfile:1
#
# Kybr API container image.
# - Build stage: JDK 25 compiles the Spring Boot fat jar with the Maven wrapper.
#   Tests are NOT run here: they need Docker (Testcontainers) and run in CI (`./mvnw verify`).
# - Runtime stage: Temurin JRE 25 (Alpine), non-root, layered jar, no secrets baked in.
# Ports: 8080 = API (published by Fly on 443), 8081 = management (health/prometheus; never published).
# Base images are pinned by tag + digest; Dependabot (docker ecosystem) proposes updates.

FROM eclipse-temurin:25-jdk-alpine@sha256:c7c3d0bdf7f2bbc5a05a6712ed334d198360105634ea9adf76fca94f381b1e3f AS build
WORKDIR /build

# Wrapper and POM first so the dependency cache layer survives source-only changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -DskipTests package

# Exactly one repackaged jar is expected in target/ (Maven keeps the thin one as *.jar.original).
RUN set -eu; \
    jars=$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar'); \
    count=$(printf '%s\n' "$jars" | grep -c . || true); \
    if [ "$count" -ne 1 ]; then echo "Expected exactly one jar in target/, found $count: $jars" >&2; exit 1; fi; \
    cp "$jars" application.jar; \
    java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918 AS runtime

LABEL org.opencontainers.image.title="kybr-api" \
      org.opencontainers.image.source="https://github.com/MatheusMelo94/kybr-backend"

# Fixed numeric non-root user (no shell, no home).
RUN addgroup -S -g 10001 app \
 && adduser -S -D -H -u 10001 -G app -s /sbin/nologin app

WORKDIR /app

# Layers ordered from least to most frequently changing. Files stay root-owned (read-only for the app user).
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

ENV PORT=8080 \
    MANAGEMENT_PORT=8081

EXPOSE 8080 8081

USER 10001:10001

# Local/compose only (Fly ignores Docker HEALTHCHECK and uses the checks in fly*.toml).
# /livez is the liveness probe on the main port: status only, no BFF key, no Mongo dependency.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/livez" || exit 1

# Container-aware memory: heap = 75% of the machine/container limit; die fast on OOM so the platform restarts us.
# Extra JVM flags can be supplied at runtime through JDK_JAVA_OPTIONS.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:InitialRAMPercentage=50.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "application.jar"]
