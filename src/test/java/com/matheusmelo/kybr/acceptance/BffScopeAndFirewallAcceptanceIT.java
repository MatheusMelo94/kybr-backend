package com.matheusmelo.kybr.acceptance;

import static com.matheusmelo.kybr.acceptance.Envelope.assertEnvelope;
import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.RawHttp;
import com.matheusmelo.kybr.support.web.WebProbeController;
import io.restassured.response.Response;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;

/**
 * BFF key scope and path variants end to end: the BFF key is required everywhere except the exact exemptions,
 * matched on the normalised path; non-normalised paths are 400 before any exemption is considered.
 */
@ExtendWith(OutputCaptureExtension.class)
class BffScopeAndFirewallAcceptanceIT extends AcceptanceTest {

    private static final String INVALID_KEY = "qa-invalid-bff-key-candidate-000000000000000";

    @LocalManagementPort
    private int managementPort;

    /** Raw path variants without a key, sent byte-exact over a socket. */
    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
        "GET,/livez/,403",
        "GET,/public/v/x/cases/y/documents,403",
        "POST,/public/v/x/cases/y/documents/,403",
        "GET,/V1/x,403",
        "GET,/webhooks/x,403",
        "GET,//livez,400",
        "GET,/v1;x=y/a,400",
        "GET,/livez;x=y,400",
        "POST,/public/v/x/cases/y%2Fdocuments,400",
        "POST,/public/v/x/%2e%2e/cases/y/documents,400",
        "POST,/public/v/x/cases/y/documents;.json,400",
        "GET,/v1/./x,400",
        "GET,/a%5Cb,400",
        "GET,/livez/%2e%2e/test/web/ok,400",
    })
    void aPathVariantIsNeverAnExemption(String method, String path, int status) throws Exception {
        int before = WebProbeController.INVOCATIONS.get();

        RawHttp.Response response = RawHttp.send(port, method + " " + path + " HTTP/1.1\r\nHost: localhost"
                + "\r\nContent-Type: application/json\r\nContent-Length: 0");

        assertThat(response.status()).as(method + " " + path).isEqualTo(status);
        Envelope.assertBody(response.header("Content-Type"), response.body(),
                status == 400 ? "REQUISICAO_INVALIDA" : "BFF_KEY_AUSENTE");
        assertThat(response.header("X-Request-Id")).matches(ResponseInvariants.UUID_V4);
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(WebProbeController.INVOCATIONS.get()).isEqualTo(before);
    }

    /** A protected handler is never reachable without a key through an encoded or case variant. */
    @ParameterizedTest
    @ValueSource(strings = {"/test/web/%6Fk", "/TEST/web/ok", "/test/web/ok/", "/test//web/ok", "/test/web/ok%00",
        "/test/web/./ok", "/livez/../test/web/ok"})
    void aProtectedHandlerIsNeverReachedWithoutAKey(String path) throws Exception {
        int before = WebProbeController.INVOCATIONS.get();

        RawHttp.Response response = RawHttp.send(port, "GET " + path + " HTTP/1.1\r\nHost: localhost");

        assertThat(response.status()).as(path).isIn(400, 403, 404);
        assertThat(WebProbeController.INVOCATIONS.get()).as(path).isEqualTo(before);
    }

    /** The document upload exemption and /livez pass without a key (404/405 envelope: no upload handler yet). */
    @Test
    void theExemptionsPassWithoutAKey() {
        Response post = httpWithoutKey().contentType("application/json").body("{}")
                .post("/public/v/x/cases/y/documents");
        Response options = httpWithoutKey().options("/public/v/x/cases/y/documents");

        assertThat(post.statusCode()).isNotEqualTo(403);
        assertThat(options.statusCode()).isNotEqualTo(403);
        assertThat(httpWithoutKey().get("/livez").statusCode()).isEqualTo(200);
    }

    /** Missing, invalid, case-flipped, twice and comma-joined keys look identical. */
    @Test
    void everyRejectedKeyGetsTheSameBodyAndHeaderNames(CapturedOutput output) {
        String flipped = Character.isUpperCase(bffKey().charAt(0))
                ? Character.toLowerCase(bffKey().charAt(0)) + bffKey().substring(1)
                : Character.toUpperCase(bffKey().charAt(0)) + bffKey().substring(1);
        if (flipped.equals(bffKey())) {
            flipped = bffKey() + "A";
        }
        Response missing = httpWithoutKey().get("/v1/x");
        Response invalid = httpWithoutKey().header(BFF_KEY_HEADER, INVALID_KEY).get("/v1/x");
        Response caseFlipped = httpWithoutKey().header(BFF_KEY_HEADER, flipped).get("/v1/x");
        Response twice = httpWithoutKey().header(BFF_KEY_HEADER, bffKey()).header(BFF_KEY_HEADER, INVALID_KEY).get("/v1/x");
        Response twiceValid = httpWithoutKey().header(BFF_KEY_HEADER, bffKey()).header(BFF_KEY_HEADER, bffKey()).get("/v1/x");
        Response comma = httpWithoutKey().header(BFF_KEY_HEADER, bffKey() + "," + bffKey()).get("/v1/x");

        for (Response r : new Response[] {missing, invalid, caseFlipped, twice, twiceValid, comma}) {
            assertEnvelope(r, 403, "BFF_KEY_AUSENTE");
            assertThat(r.asByteArray()).isEqualTo(missing.asByteArray());
            assertThat(headerNames(r)).isEqualTo(headerNames(missing));
        }
        assertThat(output.getAll()).contains("event=bff_key_rejected reason=missing",
                "event=bff_key_rejected reason=invalid", "event=bff_key_rejected reason=multiple");
        assertThat(output.getAll()).doesNotContain(bffKey(), INVALID_KEY, flipped);
    }

    /** Accepted and rejected keys are counted on the management port. */
    @Test
    void acceptedAndRejectedKeysAreCounted() {
        double accepted = Metrics.sample(managementPort, "kybr_bff_key_accepted_total", "slot=\"CURRENT\"");
        double rejected = Metrics.sample(managementPort, "kybr_bff_key_rejected_total", "reason=\"invalid\"");

        http().get("/test/web/ok");
        httpWithoutKey().header(BFF_KEY_HEADER, INVALID_KEY).get("/v1/x");

        assertThat(Metrics.sample(managementPort, "kybr_bff_key_accepted_total", "slot=\"CURRENT\""))
                .isGreaterThanOrEqualTo(accepted + 1);
        assertThat(Metrics.sample(managementPort, "kybr_bff_key_rejected_total", "reason=\"invalid\""))
                .isGreaterThanOrEqualTo(rejected + 1);
    }

    /** Any header-name case is accepted and the key is hidden from the handler. */
    @ParameterizedTest
    @ValueSource(strings = {"x-kybr-bff-key", "X-KYBR-BFF-KEY", "X-Kybr-BFF-Key"})
    void anyHeaderNameCaseWorksAndTheHandlerNeverSeesTheKey(String name, CapturedOutput output) {
        Response response = httpWithoutKey().header(name, bffKey()).get("/test/trust/headers");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("bff_key")).isEqualTo("null");
        assertThat(response.jsonPath().getList("names", String.class))
                .noneMatch(n -> n.equalsIgnoreCase(BFF_KEY_HEADER));
        assertThat(output.getAll()).doesNotContain(bffKey());
    }

    private static Set<String> headerNames(Response response) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        response.headers().forEach(h -> names.add(h.getName()));
        names.remove("Date");
        return names;
    }
}
