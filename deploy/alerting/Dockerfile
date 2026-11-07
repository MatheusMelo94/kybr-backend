# syntax=docker/dockerfile:1
# Alerting image: vmalert (evaluates the rules against Fly's managed Prometheus API) + Alertmanager
# (routes to e-mail and the dead-man check). Fly's managed Grafana has no alerting, so this small app
# does it. Base images pinned by tag + digest; Dependabot (docker ecosystem) proposes updates.
FROM prom/alertmanager:v0.34.1@sha256:e9733bafb1bdef9b00e25a21f8f99dc26a22224bf16641ad754d1649f4c3357a AS alertmanager

FROM victoriametrics/vmalert:v1.155.0@sha256:3256cba5ddaca5974eae6141105cec4ce37871f1c213f0a5c267ec0111bc68e1

# envsubst renders the templates at start; no other packages.
RUN apk add --no-cache gettext-envsubst \
 && addgroup -S -g 10001 alerting \
 && adduser -S -D -H -u 10001 -G alerting -s /sbin/nologin alerting

COPY --from=alertmanager /bin/alertmanager /usr/local/bin/alertmanager
COPY rules/ /etc/alerting/rules/
COPY alertmanager.yml.tmpl /etc/alerting/alertmanager.yml.tmpl
COPY --chmod=0755 entrypoint.sh /usr/local/bin/alerting-entrypoint

USER 10001:10001
EXPOSE 8880
ENTRYPOINT ["/usr/local/bin/alerting-entrypoint"]
