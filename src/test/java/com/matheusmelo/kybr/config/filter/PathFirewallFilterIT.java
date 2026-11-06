package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.trust.LaterFilterProbe;
import com.matheusmelo.kybr.support.web.RawHttp;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PathFirewallFilterIT extends WebIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"//livez", "/v1;jsessionid=x/a", "/a%2Fb", "/a%5Cb", "/a/%2e%2e/b", "/a/./b",
        "/a/\u0001b"})
    void aNonNormalisedPathIs400BeforeAnyLaterFilter(String rawPath) throws Exception {
        int before = LaterFilterProbe.PASSED.get();

        RawHttp.Response response = RawHttp.send(port, "GET " + rawPath + " HTTP/1.1\r\nHost: localhost");

        assertThat(response.status()).as(response.toString()).isEqualTo(400);
        assertThat(response.body()).startsWith("{\"code\":\"REQUISICAO_INVALIDA\"");
        assertThat(response.header(RequestIdFilter.HEADER)).matches(RequestIdFilterTest.UUID_V4);
        assertThat(LaterFilterProbe.PASSED.get()).isEqualTo(before);
    }

    @Test
    void aNormalPathPassesWithTheSameParseAsTheDispatcher() {
        Response response = http().get("/test/trust/path/a/b");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("firewall")).isEqualTo("/test/trust/path/a/b");
        assertThat(response.jsonPath().getString("firewall")).isEqualTo(response.jsonPath().getString("dispatcher"));
    }
}
