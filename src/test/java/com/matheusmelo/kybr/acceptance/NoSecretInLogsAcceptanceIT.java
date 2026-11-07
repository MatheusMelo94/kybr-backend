package com.matheusmelo.kybr.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.matheusmelo.kybr.config.filter.AccessLogFilter;
import com.matheusmelo.kybr.service.KeyHasher;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * No secret reaches the logs, on the {@code test} profile (pattern console): across
 * every log path B0 has, the shipped output (stdout) never contains the BFF key, a bearer, a case
 * token, a CNPJ query value, a signature, an idempotency key or a response body, in any form.
 * {@link StagingNoSecretInLogsAcceptanceIT} repeats the sweep on the ECS JSON output.
 */
@ExtendWith(OutputCaptureExtension.class)
class NoSecretInLogsAcceptanceIT extends AcceptanceTest {

    @Autowired
    private KeyHasher keyHasher;

    @Test
    void noSecretReachesTheLogs(CapturedOutput output) {
        SecretCanaries canaries = new SecretCanaries();

        canaries.sendAll(this::http, this::httpWithoutKey);

        assertThat(output.getAll()).contains("event=access");
        canaries.assertAbsentFrom(output.getAll(), bffKey());
    }

    /** One INFO access line with the template route, query names only and no header field. */
    @Test
    void theAccessLineHasTheTemplateRouteAndQueryNamesOnly() {
        try (AccessLines lines = AccessLines.capture()) {
            Response response = http().header("Authorization", "Bearer qa-not-logged")
                    .get("/public/v/s/cases/qaTokenAAAAAAAAAAAAAAAAAA?cnpj=" + SecretCanaries.CNPJ);

            Map<String, String> line = lines.lineFor(response.header("X-Request-Id"));
            assertThat(line).containsEntry("route", "/public/v/{slug}/cases/{token}")
                    .containsEntry("query_params", "[\"cnpj\"]")
                    .containsEntry("method", "GET")
                    .containsEntry("status", "404") // the real case route; slug "s" is unknown
                    .containsEntry("bff_key_slot", "CURRENT")
                    .containsKeys("duration_ms", "client_ip", "client_ip_source");
            assertThat(line.keySet()).containsExactly("event", "method", "route", "query_params", "status",
                    "duration_ms", "request_id", "client_ip", "client_ip_source", "bff_key_slot");
        }
    }

    /**
     * On an unmatched path the token is a keyed hash ({@code tok:} + 12
     * hex), stable per token and different across tokens, and is not a plain SHA-256.
     */
    @Test
    void anUnmatchedPathLogsAStableKeyedTokenHash() throws Exception {
        String tokenA = "qaTokenA" + "a".repeat(20);
        String tokenB = "qaTokenB" + "b".repeat(20);
        try (AccessLines lines = AccessLines.capture()) {
            String a1 = lines.lineFor(http().get("/public/v/s/cases/" + tokenA + "/nope").header("X-Request-Id")).get("route");
            String a2 = lines.lineFor(http().get("/public/v/s/cases/" + tokenA + "/nope").header("X-Request-Id")).get("route");
            String b = lines.lineFor(http().get("/public/v/s/cases/" + tokenB + "/nope").header("X-Request-Id")).get("route");

            String expected = "tok:" + keyHasher.hmac("casetoken:" + tokenA).substring(0, 12);
            assertThat(a1).isEqualTo(a2).contains(expected).doesNotContain(tokenA).matches(".*tok:[A-Za-z0-9_-]{12}/nope$");
            assertThat(b).doesNotContain(expected).doesNotContain(tokenB).matches(".*tok:[A-Za-z0-9_-]{12}(/.*|$)");
            String plainSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(tokenA.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
            assertThat(a1).doesNotContain(plainSha);
        }
    }

    /** Requests rejected by filters (403 BFF, 413, firewall 400) get exactly one access line each. */
    @Test
    void filterRejectionsAreAccessLoggedOnce() {
        try (AccessLines lines = AccessLines.capture()) {
            Response forbidden = httpWithoutKey().get("/v1/x");
            Response tooLarge = http().contentType("application/octet-stream").body(new byte[1_048_577]).post("/test/web/echo");
            Response firewall = http().urlEncodingEnabled(false).get("/test/web/ok;x=y");

            assertThat(lines.lineFor(forbidden.header("X-Request-Id"))).containsEntry("status", "403");
            assertThat(lines.lineFor(tooLarge.header("X-Request-Id"))).containsEntry("status", "413");
            assertThat(lines.lineFor(firewall.header("X-Request-Id"))).containsEntry("status", "400");
        }
    }

    /** Security events carry the request id. */
    @Test
    void securityEventsCarryTheRequestId() {
        Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        RootEvents appender = new RootEvents();
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
        try {
            Response rejected = httpWithoutKey().header("X-Kybr-BFF-Key", "qa-invalid").get("/v1/x");
            Response absent = http().get("/test/web/ok");

            assertThat(eventsWith(appender, "event=bff_key_rejected", rejected.header("X-Request-Id"))).isOne();
            assertThat(eventsWith(appender, "event=client_ip_absent", absent.header("X-Request-Id"))).isOne();
        } finally {
            root.detachAppender(appender);
        }
    }

    /** Root-logger events with their MDC frozen at log time. */
    private static final class RootEvents extends ch.qos.logback.core.AppenderBase<ILoggingEvent> {
        final java.util.List<ILoggingEvent> list = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            list.add(event);
        }
    }

    private static long eventsWith(RootEvents appender, String event,
            String requestId) {
        return appender.list.stream()
                .filter(e -> !e.getLoggerName().equals(AccessLogFilter.class.getName()))
                .filter(e -> e.getFormattedMessage().contains(event))
                .filter(e -> requestId.equals(e.getMDCPropertyMap().get("request_id")))
                .count();
    }
}
