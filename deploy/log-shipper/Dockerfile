# Log shipper image: pinned Vector + the project pipeline. Runs as a non-root user.
FROM timberio/vector:0.60.0-debian@sha256:3ccb4bbd36e38734394e158db1411e91a69d076ed25013f8de385046a382d36a

RUN groupadd --system --gid 10001 vector-app \
 && useradd --system --uid 10001 --gid vector-app --no-create-home --shell /usr/sbin/nologin vector-app \
 && mkdir -p /var/lib/vector \
 && chown vector-app:vector-app /var/lib/vector

COPY vector.yaml /etc/vector/vector.yaml

USER 10001:10001

ENTRYPOINT ["vector", "--config", "/etc/vector/vector.yaml"]
