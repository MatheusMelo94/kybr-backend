package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.matheusmelo.kybr.config.KybrProperties.Hashing;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.support.TestTokens;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import com.matheusmelo.kybr.support.web.RawHttp;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** One structured access line per request. */
@ExtendWith(OutputCaptureExtension.class)
class AccessLogFilterIT extends WebIntegrationTest {

    private static final String TOKEN = TestTokens.caseToken();
    private static final String CNPJ = "12345678000195";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    @Test
    void aMatchedRouteLogsTheTemplateQueryNamesAndTheRequestContext(CapturedOutput output) {
        try (CapturedEvents events = CapturedEvents.of(AccessLogFilter.class)) {
            Response response = http().header("X-Kybr-Client-IP", "198.51.100.4")
                    .get("/public/v/slug-a/cases/" + TOKEN + "?cnpj=" + CNPJ);

            // The real case route: an unknown slug is a 404, logged under the route template.
            assertThat(response.statusCode()).isEqualTo(404);
            Map<String, String> line = onlyAccessLine(events);
            assertThat(line).containsEntry("method", "GET")
                    .containsEntry("route", "/public/v/{slug}/cases/{token}")
                    .containsEntry("query_params", "[\"cnpj\"]")
                    .containsEntry("status", "404")
                    .containsEntry("request_id", response.header("X-Request-Id"))
                    .containsEntry("client_ip", "198.51.100.4")
                    .containsEntry("client_ip_source", "FORWARDED")
                    .containsEntry("bff_key_slot", "CURRENT");
            assertThat(line.get("duration_ms")).matches("\\d+");
            assertThat(line.get("request_id")).matches(UUID_PATTERN);
            assertThat(line.keySet()).noneMatch(key -> key.toLowerCase().contains("header"));
        }
        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain(CNPJ);
    }

    @Test
    void theAccessLineIsAtInfoAndItsMessageCarriesTheFields() {
        try (CapturedEvents events = CapturedEvents.of(AccessLogFilter.class)) {
            http().get("/public/v/slug-a/cases/" + TOKEN + "?cnpj=" + CNPJ);

            ILoggingEvent event = events.events().getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).startsWith("event=access ")
                    .contains("route=/public/v/{slug}/cases/{token}", "query_params=[\"cnpj\"]", "status=404")
                    .doesNotContain(TOKEN).doesNotContain(CNPJ);
        }
    }

    @Test
    void anUnmatchedPathLogsTheRedactedRawPath() {
        try (CapturedEvents events = CapturedEvents.of(AccessLogFilter.class)) {
            Response response = http().get("/public/v/slug-a/cases/" + TOKEN + "/nope?cnpj=" + CNPJ);

            assertThat(response.statusCode()).isEqualTo(404);
            Map<String, String> line = onlyAccessLine(events);
            assertThat(line.get("route")).isEqualTo("/public/v/slug-a/cases/" + tok() + "/nope");
            assertThat(line).containsEntry("query_params", "[\"cnpj\"]").containsEntry("status", "404");
            assertThat(events.events().getFirst().getFormattedMessage()).doesNotContain(TOKEN).doesNotContain(CNPJ);
        }
    }

    @Test
    void aRequestRejectedByTheBffKeyFilterIsLoggedWithItsStatusAndIp() {
        try (CapturedEvents events = CapturedEvents.of(AccessLogFilter.class)) {
            Response response = httpWithoutKey().get("/v1/anything");

            assertThat(response.statusCode()).isEqualTo(403);
            Map<String, String> line = onlyAccessLine(events);
            assertThat(line).containsEntry("status", "403").containsEntry("bff_key_slot", "NONE")
                    .containsEntry("client_ip_source", "DIRECT").containsEntry("route", "/v1/anything");
            assertThat(line.get("client_ip")).isNotEqualTo("-");
        }
    }

    @Test
    void aRawUriVariantRejectedBeforeAnyFilterIsStillLoggedWithTheTokenHashed(CapturedOutput output) throws Exception {
        try (CapturedEvents events = CapturedEvents.of(AccessLogFilter.class)) {
            RawHttp.Response response = RawHttp.send(port, "GET /public/v/s/cases%2F" + TOKEN
                    + "/contact HTTP/1.1\r\nHost: localhost\r\n" + BFF_KEY_HEADER + ": " + bffKey());

            assertThat(response.status()).isEqualTo(400);
            Map<String, String> line = onlyAccessLine(events);
            assertThat(line).containsEntry("status", "400");
            assertThat(line.get("route")).contains(tok()).doesNotContain(TOKEN);
            assertThat(line.get("request_id")).isEqualTo(response.header("X-Request-Id"));
        }
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @Test
    void aContainerErrorIsLoggedOnceWithTheFinalStatus() throws Exception {
        try (CapturedEvents events = CapturedEvents.of(AccessLogFilter.class)) {
            RawHttp.Response response = RawHttp.send(port, "GET /public/v/s/cases/" + TOKEN
                    + "/%zz HTTP/1.1\r\nHost: localhost");

            assertThat(response.status()).isEqualTo(400);
            Map<String, String> line = onlyAccessLine(events);
            assertThat(line).containsEntry("status", "400");
            assertThat(line.get("route")).doesNotContain(TOKEN);
        }
    }

    private String tok() {
        return "tok:" + new KeyHasher(new Hashing(properties.hashing().secret(), null))
                .hmac("casetoken:" + TOKEN).substring(0, 12);
    }

    /**
     * The single access event's structured fields, as the ECS writer sees them: its MDC plus its
     * key-value pairs. No name may appear in both (the writer would drop the event).
     */
    private static Map<String, String> onlyAccessLine(CapturedEvents events) {
        List<ILoggingEvent> lines = events.events();
        assertThat(lines).as("exactly one access line").hasSize(1);
        List<KeyValuePair> pairs = lines.getFirst().getKeyValuePairs();
        assertThat(pairs).as("structured fields").isNotNull();
        Map<String, String> mdc = lines.getFirst().getMDCPropertyMap();
        assertThat(pairs).as("key-value pairs repeating an MDC key").noneMatch(p -> mdc.containsKey(p.key));
        Map<String, String> fields = new HashMap<>(mdc);
        pairs.forEach(p -> fields.put(p.key, String.valueOf(p.value)));
        return fields;
    }
}
