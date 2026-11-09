# syntax=docker/dockerfile:1
#
# Isolated scanner image (separate Fly organisation): official ClamAV + stunnel + the photo sanitizer.
# The sanitizer is the API's own jar started with a different main class, so it is built here from the
# same sources. Build context: the repository root, with deploy/scanner/Dockerfile.dockerignore.
#   fly deploy . --config deploy/scanner/fly.toml --dockerfile deploy/scanner/Dockerfile \
#     --ignorefile deploy/scanner/Dockerfile.dockerignore --remote-only
# Base images pinned by tag + digest; Dependabot (docker ecosystem) proposes updates.

FROM eclipse-temurin:25-jdk-alpine@sha256:c7c3d0bdf7f2bbc5a05a6712ed334d198360105634ea9adf76fca94f381b1e3f AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package
RUN set -eu; \
    jars=$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar'); \
    count=$(printf '%s\n' "$jars" | grep -c . || true); \
    if [ "$count" -ne 1 ]; then echo "Expected exactly one jar in target/, found $count: $jars" >&2; exit 1; fi; \
    cp "$jars" application.jar; \
    java -Djarmode=tools -jar application.jar extract --destination sanitizer

FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918 AS jre

FROM clamav/clamav:1.5.4_base@sha256:58168cd4f48a118c7d20b76da8ffce97e579b84ed7e6d1e977c6e22b1e989c5a

USER root
# stunnel for the mTLS front end; tini as PID 1; su-exec to drop each process to its own user; socat for
# the supervisor's clamd health check over the Unix socket; the JRE's native dependencies for image I/O.
# Users (separation of the hostile-input decoder from the scanner):
#   clamav    clamd + freshclam; owns the signature directory; member of scanlink (socket group)
#   scanlink  stunnel; the only reader of the TLS key; reaches clamd's Unix socket through the group
#   sanitizer the photo decoder; no access to signatures (read-only), the clamd socket, the TLS key or
#             any configuration; its own temp directory
RUN apk add --no-cache stunnel tini su-exec socat zlib freetype fontconfig \
 && addgroup -S -g 10003 scanlink \
 && adduser -S -D -H -u 10003 -G scanlink -s /sbin/nologin scanlink \
 && addgroup clamav scanlink \
 && addgroup -S -g 10002 sanitizer \
 && adduser -S -D -H -u 10002 -G sanitizer -s /sbin/nologin sanitizer \
 && mkdir -p /run/kybr /opt/sanitizer /etc/stunnel

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
# The jar and its lib/ directory (manifest Class-Path), root-owned and read-only for the service user.
COPY --from=build /build/sanitizer/ /opt/sanitizer/

COPY deploy/scanner/clamav/clamd.conf /etc/clamav/clamd.conf
COPY deploy/scanner/clamav/freshclam.conf /etc/clamav/freshclam.conf
COPY deploy/scanner/clamav/kybr.ndb /etc/clamav/kybr.ndb
COPY deploy/scanner/clamav/kybr.ldb /etc/clamav/kybr.ldb
COPY deploy/scanner/stunnel.conf /etc/stunnel/kybr.conf
COPY --chmod=0755 deploy/scanner/entrypoint.sh /usr/local/bin/scanner-entrypoint
# Configuration and code are root-owned and read-only for every service user.
RUN chown -R root:root /etc/clamav /etc/stunnel /opt/sanitizer \
 && chmod -R go-w /etc/clamav /etc/stunnel /opt/sanitizer

# The supervisor starts as root only to create per-user runtime directories and drop each process to
# its own user (su-exec); it listens on nothing and handles no input.
USER root
EXPOSE 13310 13311
ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/scanner-entrypoint"]
