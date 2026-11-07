package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

/**
 * API responses are never conditional: a 304 would be turned into a 500 by the response guard, so
 * no response carries a validator and conditional request headers change nothing.
 */
class ConditionalResponsesIT extends WebIntegrationTest {

    @Test
    void conditionalRequestHeadersStillGetAFullResponseWithNoValidators() {
        Response response = http()
                .header("If-None-Match", "*")
                .header("If-Modified-Since", "Thu, 01 Jan 2026 00:00:00 GMT")
                .get("/test/web/ok");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("ETag")).isNull();
        assertThat(response.header("Last-Modified")).isNull();
        assertThat(response.jsonPath().getString("status")).isEqualTo("ok");
    }
}
