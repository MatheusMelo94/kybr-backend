package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RequestIdFilterIT extends WebIntegrationTest {

    @ParameterizedTest
    @CsvSource({"/test/web/ok,200", "/test/web/no-such-route,404", "/test/web/server-error,500"})
    void everyResponseCarriesAFreshRequestId(String path, int status) {
        String inbound = "00000000-0000-4000-8000-000000000001";

        Response response = http().header(RequestIdFilter.HEADER, inbound).get(path);

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.header(RequestIdFilter.HEADER))
                .matches(RequestIdFilterTest.UUID_V4)
                .isNotEqualTo(inbound);
    }
}
