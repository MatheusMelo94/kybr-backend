package com.matheusmelo.kybr.acceptance;

import static com.matheusmelo.kybr.acceptance.Envelope.assertEnvelope;
import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.qa.QaCnpjProbeController;
import com.matheusmelo.kybr.support.web.RawHttp;
import com.matheusmelo.kybr.support.web.WebProbeController;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The input contract over HTTP: CNPJ validation, strict JSON, unknown properties and body limits.
 * CNPJ vectors are synthetic.
 */
class InputContractAcceptanceIT extends AcceptanceTest {

    /** The four valid vectors pass the production constraint in a body. */
    @ParameterizedTest
    @ValueSource(strings = {"12345678000195", "98765432000198", "11222333000181", "12ABC34501DE35"})
    void aValidCanonicalCnpjIsAccepted(String cnpj) {
        Response response = http().contentType("application/json").body("{\"cnpj\":\"" + cnpj + "\"}").post("/test/qa/cnpj");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("cnpj")).isEqualTo(cnpj);
    }

    /** Invalid, non-canonical and boundary values are 422 in a body and never reach the handler. */
    @ParameterizedTest
    @ValueSource(strings = {"12ABC34501DE36", "12ABC34501DE3A", "00000000000000", "11111111111111", "12abc34501de35",
        "12.345.678/0001-95", "1234567800019", "123456780001950", "", " 12345678000195", "12345678000195 ",
        "１２３４５６７８０００１９５",
        "١٢٣٤٥٦٧٨٠٠٠١٩٥", "12АBC34501DE35"})
    void aNonCanonicalCnpjIs422InABody(String cnpj) {
        int before = QaCnpjProbeController.INVOCATIONS.get();

        Map<String, Object> body = assertEnvelope(http().contentType("application/json; charset=UTF-8")
                .body("{\"cnpj\":\"" + cnpj + "\"}").post("/test/qa/cnpj"), 422, "CNPJ_INVALIDO");

        assertThat(body).containsEntry("field", "cnpj");
        assertThat(QaCnpjProbeController.INVOCATIONS.get()).isEqualTo(before);
    }

    /** A JSON number is never coerced to a CNPJ string. */
    @Test
    void aJsonNumberIsNotACnpj() {
        Response response = http().contentType("application/json").body("{\"cnpj\":12345678000195}").post("/test/qa/cnpj");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.jsonPath().getString("field")).isEqualTo("cnpj");
    }

    /** CNPJ_INVALIDO is 400 on a query or path parameter. */
    @Test
    void aNonCanonicalCnpjIs400InAQueryOrPath() {
        assertThat(assertEnvelope(http().get("/test/qa/cnpj?cnpj=12ABC34501DE36"), 400, "CNPJ_INVALIDO"))
                .containsEntry("field", "cnpj");
        assertThat(assertEnvelope(http().get("/test/qa/cnpj/12abc34501de35"), 400, "CNPJ_INVALIDO"))
                .containsEntry("field", "cnpj");
        assertThat(http().get("/test/qa/cnpj/12ABC34501DE35").statusCode()).isEqualTo(200);
    }

    /** Time types are ISO 8601 with an offset; dates are YYYY-MM-DD; never epoch numbers. */
    @Test
    void timeTypesAreIsoStrings() {
        Response response = http().get("/test/qa/time");

        assertThat(response.jsonPath().getString("instant")).isEqualTo("2030-01-02T03:04:05Z");
        assertThat(response.jsonPath().getString("offset_date_time"))
                .matches("^2030-01-02T\\d{2}:04:05(Z|[+-]\\d{2}:\\d{2})$");
        assertThat(response.jsonPath().getString("local_date")).isEqualTo("2030-01-02");
    }

    /** An unknown property and a camelCase name are 422 naming the property, with the fixed message. */
    @Test
    void unknownAndCamelCasePropertiesAre422NamingTheProperty() {
        Map<String, Object> unknown = assertEnvelope(http().contentType("application/json")
                .body("{\"display_name\":\"a\",\"foo\":1}").post("/test/web/validation/body"), 422, "VALOR_INVALIDO");
        Map<String, Object> camel = assertEnvelope(http().contentType("application/json")
                .body("{\"displayName\":\"a\"}").post("/test/web/validation/body"), 422, "VALOR_INVALIDO");
        Map<String, Object> blank = assertEnvelope(http().contentType("application/json")
                .body("{\"display_name\":\"\"}").post("/test/web/validation/body"), 422, "VALOR_INVALIDO");

        assertThat(unknown).containsEntry("field", "foo");
        assertThat(camel).containsEntry("field", "displayName");
        assertThat(unknown.get("message")).isEqualTo(camel.get("message")).isEqualTo(blank.get("message"));
    }

    /** Empty, array, trailing garbage, truncated and duplicate-key bodies are 400. */
    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "{\"display_name\":\"a\"} x", "{\"display_name\":\"a\"", "{\"display_name\":\"a\",\"display_name\":\"b\"}"})
    void malformedJsonIs400(String json) {
        assertEnvelope(http().contentType("application/json").body(json).post("/test/web/validation/body"),
                400, "REQUISICAO_INVALIDA");
    }

    /** Scalar coercion is off (boolean or number to string, string to number, float to integer). */
    @ParameterizedTest
    @ValueSource(strings = {"{\"display_name\":true}", "{\"display_name\":12}", "{\"display_name\":\"a\",\"item_count\":\"3\"}",
        "{\"display_name\":\"a\",\"item_count\":1.5}"})
    void scalarsAreNotCoerced(String json) {
        assertThat(http().contentType("application/json").body(json).post("/test/web/validation/body").statusCode())
                .isIn(400, 422);
    }

    /** The 3 MiB route override accepts 2.5 MB and rejects 3 MiB + 1. */
    @Test
    void aRouteOverrideRaisesTheLimit() {
        assertThat(http().contentType("application/octet-stream").body(new byte[2_621_440]).post("/test/web/large")
                .statusCode()).isEqualTo(200);
        assertEnvelope(http().contentType("application/octet-stream").body(new byte[3_145_729]).post("/test/web/large"),
                413, "REQUISICAO_GRANDE");
    }

    /**
     * Multipart outside the document upload never reaches a handler: with a Content-Length over the
     * limit it is 413, otherwise (under the limit, or chunked of any size) 415 before any byte is read.
     */
    @Test
    void multipartOutsideDocumentUploadIsRejectedBeforeTheHandler() throws Exception {
        int before = WebProbeController.INVOCATIONS.get();

        RawHttp.Response large = RawHttp.send(port, "POST /test/web/echo HTTP/1.1\r\nHost: localhost\r\n"
                + BFF_KEY_HEADER + ": " + bffKey() + "\r\nContent-Type: multipart/form-data; boundary=x\r\n"
                + "Content-Length: " + (5 * 1024 * 1024));
        Response chunkedLarge = http().multiPart("file", "a.bin", new byte[5 * 1024 * 1024]).post("/test/web/echo");
        Response small = http().multiPart("file", "a.bin", new byte[1024]).post("/test/web/echo");

        assertThat(large.status()).isEqualTo(413);
        Envelope.assertBody(large.header("Content-Type"), large.body(), "REQUISICAO_GRANDE");
        assertEnvelope(chunkedLarge, 415, "MIDIA_NAO_SUPORTADA");
        assertEnvelope(small, 415, "MIDIA_NAO_SUPORTADA");
        assertThat(WebProbeController.INVOCATIONS.get()).isEqualTo(before);
    }
}
