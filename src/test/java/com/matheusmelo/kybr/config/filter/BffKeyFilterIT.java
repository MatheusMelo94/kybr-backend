package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.RawHttp;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class BffKeyFilterIT extends WebIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"/test/web/ok", "/v1/x", "/public/v/s", "/webhooks/x"})
    void missingAndInvalidKeysGetByteIdentical403s(String path) {
        Response missing = httpWithoutKey().get(path);
        Response invalid = httpWithoutKey().header(BFF_KEY_HEADER, "not-the-key").get(path);

        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(invalid.statusCode()).isEqualTo(403);
        assertThat(missing.jsonPath().getString("code")).isEqualTo("BFF_KEY_AUSENTE");
        assertThat(missing.asByteArray()).isEqualTo(invalid.asByteArray());
        assertThat(headerNames(missing)).isEqualTo(headerNames(invalid));
        assertThat(missing.header(RequestIdFilter.HEADER)).matches(RequestIdFilterTest.UUID_V4);
    }

    private static Set<String> headerNames(Response response) {
        return Set.copyOf(response.headers().asList().stream().map(h -> h.getName().toLowerCase()).toList());
    }

    @Test
    void livezNeedsNoKey() {
        assertThat(httpWithoutKey().get("/livez").statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @CsvSource({"POST, /public/v/x/cases/y/documents", "OPTIONS, /public/v/x/cases/y/documents"})
    void theDocumentUploadRouteIsNotRejectedForAMissingKey(String method, String path) {
        assertThat(httpWithoutKey().request(method, path).statusCode()).isNotEqualTo(403);
    }

    @ParameterizedTest
    @CsvSource({"GET, /livez/", "GET, /public/v/x/cases/y/documents", "POST, /public/v/x/cases/y/documents/"})
    void nearMissesOfAnExemptionAre403(String method, String path) {
        assertThat(httpWithoutKey().request(method, path).statusCode()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"//livez", "/v1;x=y/a", "/public/v/x/cases/y%2Fdocuments",
        "/public/v/x/%2e%2e/cases/y/documents"})
    void pathTricksAre400NeverAnExemption(String rawPath) throws Exception {
        RawHttp.Response response = RawHttp.send(port, "POST " + rawPath + " HTTP/1.1\r\nHost: localhost"
                + "\r\nContent-Length: 0");

        assertThat(response.status()).as(response.toString()).isEqualTo(400);
        assertThat(response.body()).contains("REQUISICAO_INVALIDA");
    }

    @Test
    void theHeaderNameIsCaseInsensitive() {
        assertThat(httpWithoutKey().header("x-kybr-bff-key", bffKey()).get("/test/web/ok").statusCode())
                .isEqualTo(200);
        assertThat(httpWithoutKey().header("X-KYBR-BFF-KEY", bffKey()).get("/test/web/ok").statusCode())
                .isEqualTo(200);
    }

    @Test
    void twoHeaderInstancesAreRejected() throws Exception {
        for (List<String> values : List.of(List.of(bffKey(), "not-the-key"), List.of(bffKey(), bffKey()))) {
            RawHttp.Response response = RawHttp.send(port, "GET /test/web/ok HTTP/1.1\r\nHost: localhost"
                    + "\r\nX-Kybr-BFF-Key: " + values.get(0) + "\r\nX-Kybr-BFF-Key: " + values.get(1));

            assertThat(response.status()).isEqualTo(403);
        }
    }

    @Test
    void theKeyIsHiddenFromTheHandlerAndAbsentFromTheLogs(CapturedOutput output) {
        Response response = http().get("/test/trust/headers");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("bff_key")).isEqualTo("null");
        assertThat(response.jsonPath().getList("names", String.class))
                .noneMatch(BFF_KEY_HEADER::equalsIgnoreCase);
        assertThat(output).doesNotContain(bffKey());
    }

    @Test
    void rejectionsAreLoggedWithoutTheValue(CapturedOutput output) {
        httpWithoutKey().header(BFF_KEY_HEADER, "presented-canary-value").get("/v1/x");

        assertThat(output).contains("event=bff_key_rejected reason=invalid")
                .doesNotContain("presented-canary-value");
    }
}
