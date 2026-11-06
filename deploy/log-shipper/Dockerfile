# Log shipper image: pinned Vector + the project pipeline. Runs as a non-root user.
FROM timberio/vector:0.61.0-debian@sha256:f6c23b5a7e16fb2838d33619ef7acf4bad2289a7cf22bc90efc2371f520c4ea2

RUN groupadd --system --gid 10001 vector-app \
 && useradd --system --uid 10001 --gid vector-app --no-create-home --shell /usr/sbin/nologin vector-app \
 && mkdir -p /var/lib/vector \
 && chown vector-app:vector-app /var/lib/vector

COPY vector.yaml /etc/vector/vector.yaml

USER 10001:10001

ENTRYPOINT ["vector", "--config", "/etc/vector/vector.yaml"]
