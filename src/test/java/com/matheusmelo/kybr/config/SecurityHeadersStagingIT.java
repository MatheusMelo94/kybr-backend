package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.StagingWebIntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** HSTS joins the header set in the cloud profiles. A separate context: keep it alone. */
class SecurityHeadersStagingIT extends StagingWebIntegrationTest {

    @ParameterizedTest
    @CsvSource({"/test/web/ok,200", "/test/web/no-such-route,404", "/test/web/server-error,500"})
    void hstsIsSentOnceInStaging(String path, int status) {
        Response response = http().get(path);

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().getValues("Strict-Transport-Security"))
                .containsExactly("max-age=31536000; includeSubDomains");
        SecurityConfigIT.SECURITY_HEADERS.forEach((name, value) ->
                assertThat(response.headers().getValues(name)).as(name).containsExactly(value));
    }
}
