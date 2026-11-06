package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.FilterErrorProbe;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ResponseGuardFilterIT extends WebIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"/test/web/redirect", "/test/web/found"})
    void aRedirectBecomesA500Envelope(String path, CapturedOutput output) {
        Response response = http().get(path);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.header("Location")).isNull();
        assertThat(response.jsonPath().getString("code")).isEqualTo("ERRO_INTERNO");
        assertThat(response.header(RequestIdFilter.HEADER)).matches(RequestIdFilterTest.UUID_V4);
        assertThat(output).contains("event=redirect_blocked");
    }

    @Test
    void cookiesAreDropped(CapturedOutput output) {
        Response response = http().get("/test/web/cookie");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().getValues("Set-Cookie")).isEmpty();
        assertThat(output).contains("event=cookie_blocked");
    }

    @Test
    void creatingASessionFailsAndSetsNoCookie() {
        Response response = http().get("/test/web/session");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().getValues("Set-Cookie")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"BFF_KEY_AUSENTE,403", "REQUISICAO_GRANDE,413"})
    void anErrorWrittenByALaterFilterCarriesEverySecurityHeaderOnce(String code, int status) {
        Response response = http().header(FilterErrorProbe.HEADER, code).get("/test/web/ok");

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().getValues(RequestIdFilter.HEADER)).hasSize(1);
        ResponseGuardFilterTest.SECURITY_HEADERS.forEach((name, value) ->
                assertThat(response.headers().getValues(name)).as(name).containsExactly(value));
        assertThat(response.header(ResponseGuardFilterTest.HSTS)).isNull();
    }
}
