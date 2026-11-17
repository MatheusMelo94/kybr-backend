# syntax=docker/dockerfile:1
# Alerting image: vmalert (evaluates the rules against Fly's managed Prometheus API) + Alertmanager
# (routes to e-mail and the dead-man check). Fly's managed Grafana has no alerting, so this small app
# does it. Base images pinned by tag + digest; Dependabot (docker ecosystem) proposes updates.
FROM prom/alertmanager:v0.34.2@sha256:39d8bb3966c694a124428a3a88fbcffd40bd069129ecd47c059f8a3f17c55f3b AS alertmanager

FROM victoriametrics/vmalert:v1.156.0@sha256:4fea7d1a888d4db4ff18804434ef0f3ca8eb6482e289c50d32001c2db218287c

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
