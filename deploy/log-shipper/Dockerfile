# Log shipper image: pinned Vector + the project pipeline. Runs as a non-root user.
FROM timberio/vector:0.62.0-debian@sha256:6266b1d35c844918ff1c48ea2ec4f57c2b0664c46f3e17fc2418d5dda1692068

RUN groupadd --system --gid 10001 vector-app \
 && useradd --system --uid 10001 --gid vector-app --no-create-home --shell /usr/sbin/nologin vector-app \
 && mkdir -p /var/lib/vector \
 && chown vector-app:vector-app /var/lib/vector

COPY vector.yaml /etc/vector/vector.yaml

USER 10001:10001

ENTRYPOINT ["vector", "--config", "/etc/vector/vector.yaml"]
