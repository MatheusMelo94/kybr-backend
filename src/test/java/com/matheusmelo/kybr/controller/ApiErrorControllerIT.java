package com.matheusmelo.kybr.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.filter.RequestIdFilter;
import com.matheusmelo.kybr.support.web.RawHttp;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiErrorControllerIT extends WebIntegrationTest {

    private static void assertContainerEnvelope(RawHttp.Response response, int status, String code) {
        assertThat(response.status()).as(response.toString()).isEqualTo(status);
        assertThat(response.header("Content-Type")).startsWith("application/json");
        assertThat(response.body()).startsWith("{\"code\":\"" + code + "\"")
                .doesNotContain("timestamp").doesNotContain("<html");
        assertThat(response.header(RequestIdFilter.HEADER)).isNotBlank();
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void requestingErrorDirectlyIs404(String method) {
        Response response = http().request(method, "/error");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.jsonPath().getString("code")).isEqualTo("RECURSO_NAO_ENCONTRADO");
        assertThat(response.header(RequestIdFilter.HEADER)).isNotBlank();
    }

    @Test
    void anOversizedHeaderGetsTheEnvelope() throws Exception {
        String big = "a".repeat(17 * 1024);

        RawHttp.Response response = RawHttp.send(port,
                "GET /test/web/ok HTTP/1.1\r\nHost: localhost\r\nX-Big: " + big);

        assertContainerEnvelope(response, 400, "REQUISICAO_INVALIDA");
    }

    @Test
    void aMalformedPercentEncodingGetsTheEnvelope() throws Exception {
        RawHttp.Response response = RawHttp.send(port, "GET /test/web/%zz HTTP/1.1\r\nHost: localhost");

        assertContainerEnvelope(response, 400, "REQUISICAO_INVALIDA");
    }

    @Test
    void anEncodedSlashGetsTheEnvelope() throws Exception {
        RawHttp.Response response = RawHttp.send(port, "GET /test/web/a%2Fb HTTP/1.1\r\nHost: localhost");

        assertContainerEnvelope(response, 400, "REQUISICAO_INVALIDA");
    }

    @Test
    void aBrokenRequestLineGetsTheEnvelope() throws Exception {
        RawHttp.Response response = RawHttp.send(port, "GET /test/web/ok HTTP/1.1 extra\r\nHost: localhost");

        assertContainerEnvelope(response, 400, "REQUISICAO_INVALIDA");
    }

    @Test
    void aControlCharacterInThePathGetsTheEnvelope() throws Exception {
        RawHttp.Response response = RawHttp.send(port, "GET /test/web/a\u0001b HTTP/1.1\r\nHost: localhost");

        assertContainerEnvelope(response, 400, "REQUISICAO_INVALIDA");
    }

    @Test
    void aNonHttpByteStreamGetsTheEnvelope() throws Exception {
        byte[] tlsClientHello = {0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x01, 0x00};

        RawHttp.Response response = RawHttp.send(port, tlsClientHello, new byte[0]);

        assertContainerEnvelope(response, 400, "REQUISICAO_INVALIDA");
    }

    @Test
    @Disabled("Accepted, documented limitation: when the client closes the connection before the"
            + " request is complete, Tomcat writes no response at all, so there is nothing to envelope."
            + " Every case Tomcat does answer (oversized header, bad encoding, %2F, control character,"
            + " broken request line, non-HTTP bytes, TRACE) is covered above.")
    void aConnectionClosedMidRequestGetsNoResponse() {
    }

    @Test
    void aTraceRequestGetsTheEnvelope() throws Exception {
        RawHttp.Response response = RawHttp.send(port, "TRACE /test/web/ok HTTP/1.1\r\nHost: localhost");

        assertContainerEnvelope(response, 405, "METODO_NAO_PERMITIDO");
    }
}
