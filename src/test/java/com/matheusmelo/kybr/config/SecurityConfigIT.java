package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.filter.RequestIdFilter;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityConfigIT extends WebIntegrationTest {

    static final Map<String, String> SECURITY_HEADERS = Map.of(
            "X-Content-Type-Options", "nosniff",
            "Cache-Control", "no-store",
            "Referrer-Policy", "no-referrer",
            "X-Frame-Options", "DENY",
            "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");

    @ParameterizedTest
    @ValueSource(strings = {"/", "/v1", "/v1/", "/login", "/logout", "/test/web/ok", "/test/web/no-such-route",
        "/test/web/server-error", "/test/web/denied/x", "/swagger-ui.html", "/v3/api-docs/", "/actuator"})
    void noPathRedirectsSetsACookieOrChallenges(String path) {
        for (String method : new String[] {"GET", "POST"}) {
            Response response = http().request(method, path);

            assertThat(response.statusCode() / 100).as(method + " " + path).isNotEqualTo(3);
            assertThat(response.header("Location")).isNull();
            assertThat(response.headers().getValues("Set-Cookie")).isEmpty();
            assertThat(response.header("WWW-Authenticate")).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({"/test/web/ok,200", "/test/web/no-such-route,404", "/test/web/server-error,500",
        "/test/web/denied/x,404"})
    void theHeaderSetIsPresentExactlyOnce(String path, int status) {
        Response response = http().get(path);

        assertThat(response.statusCode()).isEqualTo(status);
        SECURITY_HEADERS.forEach((name, value) ->
                assertThat(response.headers().getValues(name)).as(name).containsExactly(value));
        assertThat(response.header("Strict-Transport-Security")).isNull();
        assertThat(response.headers().getValues("Pragma")).isEmpty();
    }

    /** A deny looks like not found (404), not 403, so it reveals nothing. */
    @Test
    void aDenyAllRouteLooksExactlyLikeAnUnknownRoute() {
        Response denied = http().accept("text/html").get("/test/web/denied/x");
        Response unknown = http().get("/test/web/no-such-route");

        assertThat(denied.statusCode()).isEqualTo(404);
        assertThat(denied.contentType()).startsWith("application/json");
        assertThat(denied.jsonPath().getString("code")).isEqualTo("RECURSO_NAO_ENCONTRADO");
        assertThat(denied.asByteArray()).isEqualTo(unknown.asByteArray());
        assertThat(denied.header(RequestIdFilter.HEADER)).isNotBlank();
    }

    @Test
    void aPostWithoutACsrfTokenIsNotRejectedByCsrf() {
        assertThat(http().post("/test/web/ok").statusCode()).isEqualTo(405);
    }
}
