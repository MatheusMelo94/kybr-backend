package com.matheusmelo.kybr.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Client-IP spoofing end to end with {@code KYBR_CLIENT_IP_PLATFORM_HEADER=Fly-Client-IP}: the
 * <b>access-log line</b> (what the deploy-time spoof script reads) records the IP actually used and
 * its source.
 *
 * <p>Locally, {@code Fly-Client-IP} stands for the value Fly's edge writes. Proving that Fly
 * <i>overwrites</i> a forged {@code Fly-Client-IP} needs the real edge: a pre-deploy check on staging only.
 * Addresses are RFC 5737 / RFC 3849 documentation ranges.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClientIpSpoofAcceptanceIT extends StagingAcceptanceTest {

    private static final String CLIENT_IP = "X-Kybr-Client-IP";
    private static final String PLATFORM = "Fly-Client-IP";
    private static final String EDGE_IP = "198.51.100.7";

    /** Case 1, through the BFF: the keyed header wins; X-Forwarded-For and the platform IP don't. */
    @Test
    void throughTheBffTheAccessLineRecordsTheForwardedIp(CapturedOutput output) {
        try (AccessLines lines = AccessLines.capture()) {
            Response response = http()
                    .header(CLIENT_IP, "198.51.100.4")
                    .header("X-Forwarded-For", "203.0.113.7")
                    .header(PLATFORM, "203.0.113.8")
                    .get("/test/trust/client-ip");

            assertThat(response.statusCode()).isEqualTo(200);
            Map<String, String> line = lines.lineFor(response.header("X-Request-Id"));
            assertThat(line).containsEntry("client_ip", "198.51.100.4")
                    .containsEntry("client_ip_source", "FORWARDED")
                    .containsEntry("bff_key_slot", "CURRENT")
                    .containsEntry("status", "200");
        }
        assertThat(output.getAll()).doesNotContain("203.0.113.7");
    }

    /** Case 2, direct to the API: /livez (200) and a keyed route (403) both record the edge IP as DIRECT. */
    @ParameterizedTest
    @CsvSource({"/livez,200", "/v1/anything,403"})
    void directToTheApiTheAccessLineRecordsThePlatformIpAsDirect(String path, int status, CapturedOutput output) {
        try (AccessLines lines = AccessLines.capture()) {
            Response response = httpWithoutKey()
                    .header(PLATFORM, EDGE_IP)
                    .header("X-Forwarded-For", "203.0.113.7")
                    .header(CLIENT_IP, "203.0.113.9")
                    .get(path);

            assertThat(response.statusCode()).isEqualTo(status);
            Map<String, String> line = lines.lineFor(response.header("X-Request-Id"));
            assertThat(line).containsEntry("client_ip", EDGE_IP)
                    .containsEntry("client_ip_source", "DIRECT")
                    .containsEntry("status", String.valueOf(status));
        }
        assertThat(output.getAll()).doesNotContain("203.0.113.7", "203.0.113.9");
    }

    /** No platform header (traffic that bypassed the edge) is PLATFORM_ABSENT, never trusted. */
    @Test
    void aMissingPlatformHeaderIsPlatformAbsentInTheAccessLine() {
        try (AccessLines lines = AccessLines.capture()) {
            Response response = httpWithoutKey().header(CLIENT_IP, "203.0.113.9").get("/v1/anything");

            Map<String, String> line = lines.lineFor(response.header("X-Request-Id"));
            assertThat(line).containsEntry("client_ip_source", "PLATFORM_ABSENT");
            assertThat(line.get("client_ip")).isNotEqualTo("203.0.113.9");
        }
    }

    /** Two platform header instances, or an unparseable one, are PLATFORM_ABSENT too. */
    @ParameterizedTest
    @ValueSource(strings = {"multiple", "comma", "garbage"})
    void aMalformedPlatformHeaderIsPlatformAbsent(String variant, CapturedOutput output) {
        var request = httpWithoutKey();
        switch (variant) {
            case "multiple" -> request.header(PLATFORM, EDGE_IP).header(PLATFORM, "203.0.113.8");
            case "comma" -> request.header(PLATFORM, EDGE_IP + ", 203.0.113.8");
            default -> request.header(PLATFORM, "not-an-ip");
        }
        try (AccessLines lines = AccessLines.capture()) {
            Response response = request.get("/livez");

            Map<String, String> line = lines.lineFor(response.header("X-Request-Id"));
            assertThat(line).containsEntry("client_ip_source", "PLATFORM_ABSENT");
            assertThat(line.get("client_ip")).isNotIn(EDGE_IP, "203.0.113.8");
        }
        assertThat(output.getAll()).contains("event=client_ip_platform_absent");
    }

    /** Without a valid key the keyed header is ignored, even with an invalid key on an exempt route. */
    @ParameterizedTest
    @ValueSource(strings = {"none", "invalid"})
    void withoutAValidKeyTheForwardedHeaderIsIgnored(String key, CapturedOutput output) {
        var request = httpWithoutKey();
        if (key.equals("invalid")) {
            request.header(BFF_KEY_HEADER, "qa-invalid-key-candidate-0000000000000000000");
        }
        try (AccessLines lines = AccessLines.capture()) {
            Response response = request.header(PLATFORM, EDGE_IP).header(CLIENT_IP, "203.0.113.9").get("/livez");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(lines.lineFor(response.header("X-Request-Id")))
                    .containsEntry("client_ip", EDGE_IP).containsEntry("client_ip_source", "DIRECT");
        }
        assertThat(output.getAll()).contains("event=client_ip_untrusted").doesNotContain("qa-invalid-key-candidate");
    }

    /** A keyed request without a usable header falls back to the edge IP as FORWARDED_ABSENT. */
    @ParameterizedTest
    @ValueSource(strings = {"<absent>", "", "198.51.100.4:80", "[2001:db8::1]:443", "fe80::1%eth0",
        "example.invalid", "198.51.100.4, 203.0.113.7", "999.1.1.1", "<twice>"})
    void aKeyedRequestWithoutAUsableHeaderIsForwardedAbsent(String value) {
        var request = http().header(PLATFORM, EDGE_IP);
        if (value.equals("<twice>")) {
            request.header(CLIENT_IP, "198.51.100.4").header(CLIENT_IP, "198.51.100.5");
        } else if (!value.equals("<absent>")) {
            request.header(CLIENT_IP, value);
        }
        try (AccessLines lines = AccessLines.capture()) {
            Response response = request.get("/test/trust/client-ip");

            assertThat(response.statusCode()).as("a missing header never fails the request").isEqualTo(200);
            assertThat(lines.lineFor(response.header("X-Request-Id")))
                    .containsEntry("client_ip", EDGE_IP).containsEntry("client_ip_source", "FORWARDED_ABSENT");
        }
    }

    /** Non-canonical literals are logged canonically. */
    @ParameterizedTest
    @CsvSource({"2001:DB8:0:0:0:0:0:1,2001:db8::1", "::ffff:198.51.100.4,198.51.100.4"})
    void aNonCanonicalLiteralIsLoggedCanonically(String sent, String logged) {
        try (AccessLines lines = AccessLines.capture()) {
            Response response = http().header(PLATFORM, EDGE_IP).header(CLIENT_IP, sent).get("/test/trust/client-ip");

            assertThat(lines.lineFor(response.header("X-Request-Id")))
                    .containsEntry("client_ip", logged).containsEntry("client_ip_source", "FORWARDED");
        }
    }

    /** X-Forwarded-For, Forwarded and X-Real-IP never become the client IP. */
    @Test
    void proxyHeadersAreNeverTrusted(CapturedOutput output) {
        try (AccessLines lines = AccessLines.capture()) {
            Response keyed = http().header(PLATFORM, EDGE_IP)
                    .header("X-Forwarded-For", "203.0.113.21").header("Forwarded", "for=203.0.113.22")
                    .header("X-Real-IP", "203.0.113.23").get("/test/trust/client-ip");
            Response direct = httpWithoutKey().header(PLATFORM, EDGE_IP)
                    .header("X-Forwarded-For", "203.0.113.21").header("Forwarded", "for=203.0.113.22")
                    .header("X-Real-IP", "203.0.113.23").get("/livez");

            assertThat(lines.lineFor(keyed.header("X-Request-Id"))).containsEntry("client_ip", EDGE_IP);
            assertThat(lines.lineFor(direct.header("X-Request-Id"))).containsEntry("client_ip", EDGE_IP);
        }
        assertThat(output.getAll()).doesNotContain("203.0.113.21", "203.0.113.22", "203.0.113.23");
    }

    /** Staging also sends HSTS on every response. */
    @Test
    void stagingResponsesCarryHsts() {
        assertThat(httpWithoutKey().header(PLATFORM, EDGE_IP).get("/livez").header("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");
    }
}
