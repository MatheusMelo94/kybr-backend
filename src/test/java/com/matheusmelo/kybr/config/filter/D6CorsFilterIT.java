package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.RawHttp;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

/**
 * The upload route's CORS headers on responses written by the container, the filters and the
 * handlers alike; every status of the upload itself is covered with the endpoint.
 */
class D6CorsFilterIT extends WebIntegrationTest {

    private static final String ALLOWED = "https://kybr.com.br";
    private static final String UPLOAD = "/public/v/sem-link-x/cases/AAAAAAAAAAAAAAAAAAAAAA/documents";
    private static final int CAP = 10_747_904;

    private static void assertAllowed(Response response) {
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(response.header("Access-Control-Expose-Headers")).isEqualTo("Retry-After, X-Request-Id");
        assertThat(response.headers().getValues("Vary")).contains("Origin");
        assertThat(response.header("Access-Control-Allow-Credentials")).isNull();
    }

    private static void assertNoCors(Response response) {
        assertThat(response.headers().asList()).noneMatch(h -> h.getName().startsWith("Access-Control-"));
    }

    @Test
    void theBodyCapsTooLargeAnswerCarriesTheHeaders() throws Exception {
        RawHttp.Response response = RawHttp.send(port, "POST " + UPLOAD + " HTTP/1.1\r\nHost: localhost\r\nOrigin: "
                + ALLOWED + "\r\nContent-Type: multipart/form-data; boundary=x\r\nContent-Length: " + (CAP + 1));

        assertThat(response.status()).as(response.toString()).isEqualTo(413);
        assertThat(response.body()).startsWith("{\"code\":\"REQUISICAO_GRANDE\"");
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(response.header("Access-Control-Expose-Headers")).isEqualTo("Retry-After, X-Request-Id");
        assertThat(response.header("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void theMultipartOnlyRefusalCarriesTheHeaders() {
        Response response = httpWithoutKey().header("Origin", ALLOWED)
                .contentType("application/json").body("{}").post(UPLOAD);

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.jsonPath().getString("code")).isEqualTo("MIDIA_NAO_SUPORTADA");
        assertAllowed(response);
    }

    @Test
    void theFirewallRefusalCarriesTheHeaders() throws Exception {
        RawHttp.Response response = RawHttp.send(port,
                "FOO " + UPLOAD + " HTTP/1.1\r\nHost: localhost\r\nOrigin: " + ALLOWED);

        assertThat(response.status()).as(response.toString()).isEqualTo(400);
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(response.header("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void aDisallowedOriginIsProcessedLikeNoOriginAndNeverGetsA403() {
        Response withoutOrigin = httpWithoutKey().contentType("application/json").body("{}").post(UPLOAD);
        Response disallowed = httpWithoutKey().header("Origin", "https://evil.example")
                .contentType("application/json").body("{}").post(UPLOAD);

        assertThat(disallowed.statusCode()).isEqualTo(withoutOrigin.statusCode()).isEqualTo(415);
        assertThat(disallowed.body().asString()).doesNotContain("Invalid CORS request");
        assertThat(disallowed.headers().getValues("Vary")).contains("Origin");
        assertNoCors(disallowed);
    }

    @Test
    void anAllowedPreflightIs204WithTheAllowHeaders() {
        Response response = httpWithoutKey().header("Origin", ALLOWED)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .options(UPLOAD);

        assertThat(response.statusCode()).isEqualTo(204);
        assertAllowed(response);
        assertThat(response.header("Access-Control-Allow-Methods")).isEqualTo("POST, OPTIONS");
        assertThat(response.header("Access-Control-Allow-Headers")).isEqualTo("Content-Type");
        assertThat(response.header("Access-Control-Max-Age")).isEqualTo("600");
        assertThat(response.header(RequestIdFilter.HEADER)).matches(RequestIdFilterTest.UUID_V4);
    }

    @Test
    void aDisallowedPreflightIs204WithoutAllowHeaders() {
        Response response = httpWithoutKey().header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .options(UPLOAD);

        assertThat(response.statusCode()).isEqualTo(204);
        assertNoCors(response);
    }

    @Test
    void noOtherRouteEmitsCorsHeaders() {
        Response link = http().header("Origin", ALLOWED).get("/public/v/sem-link-x");
        Response preflight = httpWithoutKey().header("Origin", ALLOWED)
                .header("Access-Control-Request-Method", "POST").options("/public/v/sem-link-x/cases");
        Response variant = httpWithoutKey().header("Origin", ALLOWED)
                .contentType("application/json").body("{}").post(UPLOAD + "/");

        assertNoCors(link);
        assertNoCors(preflight);
        assertNoCors(variant);
        assertThat(preflight.statusCode()).isNotEqualTo(204);
    }
}
