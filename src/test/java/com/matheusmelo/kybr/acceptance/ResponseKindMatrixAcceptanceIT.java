package com.matheusmelo.kybr.acceptance;

import static com.matheusmelo.kybr.acceptance.Envelope.assertEnvelope;
import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.RawHttp;
import com.matheusmelo.kybr.support.web.WebProbeController;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * For every response kind B0 can
 * produce over HTTP, the envelope shape, {@code X-Request-Id}, no 3xx, no {@code Set-Cookie} and the
 * security headers. The per-response invariants are checked for every row by
 * {@link ResponseInvariants}; each test asserts its row's status and envelope.
 */
class ResponseKindMatrixAcceptanceIT extends AcceptanceTest {

    @Test
    void the200ProbeIsStatusOnlyAndCarriesThePipelineHeaders() {
        Response response = httpWithoutKey().get("/livez");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(response.header("Strict-Transport-Security")).as("no HSTS on the test profile").isNull();
    }

    @Test
    void aNonNormalisedPathIs400FromTheFirewall() {
        assertEnvelope(http().urlEncodingEnabled(false).get("/test/web/ok;jsessionid=x"), 400, "REQUISICAO_INVALIDA");
    }

    @Test
    void malformedJsonIs400() {
        assertEnvelope(http().contentType("application/json").body("{\"display_name\":").post("/test/web/validation/body"),
                400, "REQUISICAO_INVALIDA");
    }

    @Test
    void aMissingBffKeyIs403() {
        assertEnvelope(httpWithoutKey().get("/test/web/ok"), 403, "BFF_KEY_AUSENTE");
    }

    @Test
    void anUnknownRouteIs404() {
        assertEnvelope(http().get("/test/qa/no-such-route"), 404, "RECURSO_NAO_ENCONTRADO");
    }

    @Test
    void aWrongMethodIs405WithAllow() {
        Response response = http().delete("/test/web/ok");

        assertEnvelope(response, 405, "METODO_NAO_PERMITIDO");
        assertThat(response.header("Allow")).contains("GET");
    }

    @Test
    void anAcceptExcludingJsonIs406WithAJsonEnvelope() {
        assertEnvelope(http().accept("text/html").get("/test/web/ok"), 406, "FORMATO_NAO_ACEITO");
    }

    @Test
    void aBodyOverTheLimitIs413AndNeverReachesTheHandler() {
        int before = WebProbeController.INVOCATIONS.get();

        Response response = http().contentType("application/octet-stream").body(new byte[1_048_577])
                .post("/test/web/echo");

        assertEnvelope(response, 413, "REQUISICAO_GRANDE");
        assertThat(WebProbeController.INVOCATIONS.get()).isEqualTo(before);
    }

    @Test
    void aNonJsonBodyOnAJsonRouteIs415() {
        assertEnvelope(http().contentType("text/plain").body("display_name=x").post("/test/web/validation/body"),
                415, "MIDIA_NAO_SUPORTADA");
    }

    @Test
    void aValidationFailureIs422WithTheSnakeCaseField() {
        Map<String, Object> body = assertEnvelope(http().contentType("application/json")
                .body("{\"display_name\":\"\"}").post("/test/web/validation/body"), 422, "VALOR_INVALIDO");

        assertThat(body).containsEntry("field", "display_name");
    }

    @Test
    void aRateLimitDenialIs429WithRetryAfter() {
        String policy = "qa-matrix-" + UUID.randomUUID();
        http().get("/test/qa/rate/" + policy + "?limit=1");

        Response denied = http().get("/test/qa/rate/" + policy + "?limit=1");

        assertEnvelope(denied, 429, "LIMITE_TENTATIVAS");
        assertThat(denied.header("Retry-After")).matches("^\\d+$");
    }

    @Test
    void anUnhandledExceptionIs500WithoutDetail() {
        Response response = http().get("/test/web/server-error");

        assertEnvelope(response, 500, "ERRO_INTERNO");
        assertThat(response.asString()).doesNotContain("probe failure", "IllegalStateException", "at com.");
    }

    @Test
    void mongoUnavailableIs503() {
        assertEnvelope(http().get("/test/web/validation/mongo-down"), 503, "INDISPONIVEL");
    }

    /** Container row: Tomcat rejects the request; the error dispatch renders the envelope. */
    @Test
    void aContainerLevelRejectionIsTheEnvelopeWithTheHeaders() throws Exception {
        RawHttp.Response response = RawHttp.send(port,
                "GET /test/web/ok HTTP/1.1\r\nHost: localhost\r\nX-Big: " + "a".repeat(17 * 1024));

        assertThat(response.status()).isIn(400, 431);
        Envelope.assertBody(response.header("Content-Type"), response.body(), "REQUISICAO_INVALIDA");
        assertThat(response.header("X-Request-Id")).matches(ResponseInvariants.UUID_V4);
        ResponseInvariants.SECURITY_HEADERS.forEach((name, value) ->
                assertThat(response.header(name)).as(name).isEqualTo(value));
        assertThat(response.header("Set-Cookie")).isNull();
    }

    /** The id is always fresh, never taken from the client. */
    @Test
    void anInboundRequestIdIsNeverEchoed() {
        String inbound = "00000000-0000-4000-8000-000000000001";

        Response first = httpWithoutKey().header("X-Request-Id", inbound).get("/livez");
        Response second = httpWithoutKey().get("/livez");

        assertThat(first.header("X-Request-Id")).isNotEqualTo(inbound).isNotEqualTo(second.header("X-Request-Id"));
    }

    /** 50 concurrent requests on virtual threads; each access line carries its own response's id. */
    @Test
    void concurrentRequestsNeverShareARequestIdInTheAccessLog() throws Exception {
        try (AccessLines lines = AccessLines.capture()) {
            List<Future<Response>> futures = new ArrayList<>();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 50; i++) {
                    String path = i % 2 == 0 ? "/test/web/ok" : "/test/qa/no-such-route";
                    futures.add(pool.submit(() -> http().get(path)));
                }
            }
            for (Future<Response> future : futures) {
                Response response = future.get();
                Map<String, String> line = lines.lineFor(response.header("X-Request-Id"));
                assertThat(line.get("status")).isEqualTo(String.valueOf(response.statusCode()));
            }
        }
    }

    /** An exception message with a canary never reaches the response. */
    @Test
    void anExceptionMessageNeverReachesTheBody() {
        String canary = "qacanary" + UUID.randomUUID().toString().replace("-", "");

        Response response = http().get("/test/qa/throw/" + canary);

        assertEnvelope(response, 500, "ERRO_INTERNO");
        assertThat(response.asString()).doesNotContain(canary, "Bearer", "Exception");
    }

    /** A 404 never echoes the requested path or query. */
    @Test
    void a404NeverEchoesThePathOrQuery() {
        String canary = "qa404canary" + UUID.randomUUID().toString().replace("-", "");

        Response response = http().get("/nope/" + canary + "?q=" + canary);

        assertEnvelope(response, 404, "RECURSO_NAO_ENCONTRADO");
        assertThat(response.asString()).doesNotContain(canary, "/nope");
    }

    /** Requested directly, /error is just an unknown route. */
    @Test
    void theErrorPathItselfIsNotARoute() {
        assertEnvelope(http().get("/error"), 404, "RECURSO_NAO_ENCONTRADO");
    }

    /** Redirect-prone paths never answer 3xx (with and without the key). */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/v1", "/v1/", "/public/v/s/", "/livez/", "/login", "/logout", "/swagger-ui.html",
        "/v3/api-docs/", "/actuator", "/test/web/redirect", "/test/web/found"})
    void redirectPronePathsNeverRedirect(String path) {
        Response keyed = http().get(path);
        Response unkeyed = httpWithoutKey().get(path);

        assertThat(keyed.statusCode() / 100).isNotEqualTo(3);
        assertThat(unkeyed.statusCode() / 100).isNotEqualTo(3);
    }

    /** The key is a transport gate, never an authorization grant. */
    @Test
    void aValidKeyOnADenyAllRouteGrantsNothing() {
        Response response = http().get("/test/web/denied/anything");

        assertThat(response.statusCode()).isIn(403, 404);
        assertThat(response.contentType()).startsWith("application/json");
    }

    /** Cross-check: a handler that adds cookies and a session leaks neither. */
    @Test
    void cookieAndSessionAttemptsLeakNothing() {
        Response cookie = http().get("/test/web/cookie");
        Response session = http().get("/test/web/session");

        assertThat(cookie.statusCode()).isEqualTo(200);
        assertThat(session.statusCode()).isGreaterThanOrEqualTo(500);
    }
}
