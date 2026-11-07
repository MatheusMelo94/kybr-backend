package com.matheusmelo.kybr.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.matheusmelo.kybr.config.filter.RequestIdFilter;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import com.matheusmelo.kybr.support.web.ValidationProbeController;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerIT extends WebIntegrationTest {

    private static final String BODY = "/test/web/validation/body";

    private Response postJson(String json) {
        return http().contentType("application/json").body(json).post(BODY);
    }

    private static void assertEnvelope(Response response, int status, String code, String field) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.contentType()).startsWith("application/json");
        Map<String, Object> body = response.jsonPath().getMap("$");
        assertThat(body.get("code")).isEqualTo(code);
        assertThat(body.get("message")).isEqualTo(ErrorCode.valueOf(code).message());
        if (field == null) {
            assertThat(body).containsOnlyKeys("code", "message");
        } else {
            assertThat(body).containsOnlyKeys("code", "message", "field").containsEntry("field", field);
        }
        assertThat(response.header(RequestIdFilter.HEADER)).isNotBlank();
    }

    @Test
    void aBeanValidationFailureIs422WithTheSnakeCaseField() {
        assertEnvelope(postJson("{\"display_name\":\"\"}"), 422, "VALOR_INVALIDO", "display_name");
    }

    @Test
    void aNestedBeanValidationFailureNamesTheSnakeCasePath() {
        assertEnvelope(postJson("{\"display_name\":\"x\",\"nested\":{\"short_code\":\"abcd\"}}"),
                422, "VALOR_INVALIDO", "nested.short_code");
    }

    @Test
    void anUnknownPropertyIs422WithItsName() {
        assertEnvelope(postJson("{\"display_name\":\"x\",\"is_admin\":true}"), 422, "VALOR_INVALIDO", "is_admin");
    }

    @Test
    void aWrongScalarTypeIs422WithTheFieldAndItsConstraintsOwnCode() {
        assertEnvelope(postJson("{\"display_name\":\"x\",\"cnpj\":12345678000195}"), 422, "CNPJ_INVALIDO", "cnpj");
    }

    @Test
    void aWrongScalarTypeOnAFieldWithoutACodedConstraintIsValorInvalido() {
        assertEnvelope(postJson("{\"display_name\":\"x\",\"item_count\":\"three\"}"), 422, "VALOR_INVALIDO",
                "item_count");
    }

    @Test
    void aFloatForAnIntegerIs422WithTheFieldNotTruncated() {
        assertEnvelope(postJson("{\"display_name\":\"x\",\"item_count\":1.5}"), 422, "VALOR_INVALIDO", "item_count");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"display_name\":", "{\"display_name\":\"x\",\"display_name\":\"y\"}",
        "{\"display_name\":\"x\"} {}", "not json"})
    void malformedJsonIs400(String json) {
        assertEnvelope(postJson(json), 400, "REQUISICAO_INVALIDA", null);
    }

    @Test
    void aMissingBodyIs400() {
        assertEnvelope(http().contentType("application/json").post(BODY), 400, "REQUISICAO_INVALIDA", null);
    }

    @Test
    void aValidBodyPasses() {
        assertThat(postJson("{\"display_name\":\"x\",\"cnpj\":\"12345678000195\"}").statusCode()).isEqualTo(200);
    }

    @Test
    void noRouteIs404WithoutEchoingThePath() {
        Response response = http().get("/test/web/no-such-route/canary-path-segment?q=canary-query");

        assertEnvelope(response, 404, "RECURSO_NAO_ENCONTRADO", null);
        assertThat(response.asString()).doesNotContain("canary");
    }

    @Test
    void aWrongMethodIs405WithAllow() {
        Response response = http().post("/test/web/ok");

        assertEnvelope(response, 405, "METODO_NAO_PERMITIDO", null);
        assertThat(response.header("Allow")).contains("GET");
    }

    @Test
    void aWrongContentTypeIs415() {
        assertEnvelope(http().contentType("text/plain").body("x").post(BODY), 415, "MIDIA_NAO_SUPORTADA", null);
    }

    @Test
    void acceptTextHtmlOnAJsonRouteIs406AsJson() {
        assertEnvelope(http().accept("text/html").get("/test/web/ok"), 406, "FORMATO_NAO_ACEITO", null);
    }

    @Test
    void acceptTextHtmlOnAThrowingRouteStillGetsTheJsonEnvelope() {
        assertEnvelope(http().accept("text/html").get("/test/web/server-error"), 500, "ERRO_INTERNO", null);
        assertEnvelope(http().accept("text/html").get("/test/web/client-error"), 422, "VALOR_INVALIDO", "probe");
    }

    @Test
    void anApiExceptionRendersItsCodeFieldAndHeaders() {
        assertEnvelope(http().get("/test/web/client-error"), 422, "VALOR_INVALIDO", "probe");

        Response limited = http().get("/test/web/validation/rate-limited");
        assertEnvelope(limited, 429, "LIMITE_TENTATIVAS", null);
        assertThat(limited.header("Retry-After")).isEqualTo("42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/test/web/validation/mongo-down", "/test/web/validation/mongo-timeout"})
    void anUnreachableMongoIs503(String path) {
        assertEnvelope(http().get(path), 503, "INDISPONIVEL", null);
    }

    @Test
    void anUnexpectedExceptionIs500WithTheGenericMessageAndALoggedRequestId() {
        try (CapturedEvents captured = CapturedEvents.of(GlobalExceptionHandler.class)) {
            Response response = http().get("/test/web/server-error");

            assertEnvelope(response, 500, "ERRO_INTERNO", null);
            assertThat(response.asString()).doesNotContain("probe failure").doesNotContain("Exception");
            ILoggingEvent event = captured.events().stream()
                    .filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
            assertThat(event.getMDCPropertyMap())
                    .containsEntry(RequestIdFilter.MDC_KEY, response.header(RequestIdFilter.HEADER));
        }
    }

    @Test
    void aMongoExceptionIsLoggedAsClassAndCodeOnly(CapturedOutput output) {
        try (CapturedEvents captured = CapturedEvents.of(GlobalExceptionHandler.class)) {
            Response response = http().get("/test/web/validation/mongo-duplicate");

            assertEnvelope(response, 500, "ERRO_INTERNO", null);
            assertThat(captured.events()).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).contains("MongoWriteException").contains("11000");
            });
        }
        assertThat(output).doesNotContain(ValidationProbeController.CANARY_EMAIL);
    }

    @ParameterizedTest
    @CsvSource({
        "/test/web/validation/query?cnpj=12abc34501de35, 400, cnpj",
        "/test/web/validation/path/12abc34501de35, 400, cnpj"
    })
    void aCnpjViolationOnAQueryOrPathParameterIs400(String path, int status, String field) {
        assertEnvelope(http().get(path), status, "CNPJ_INVALIDO", field);
    }

    @Test
    void aCnpjViolationInTheBodyIs422() {
        assertEnvelope(postJson("{\"display_name\":\"x\",\"cnpj\":\"12abc34501de35\"}"), 422, "CNPJ_INVALIDO", "cnpj");
    }

    @Test
    void anotherCodeOnAQueryParameterKeepsItsDefault422() {
        assertEnvelope(http().get("/test/web/validation/query?cnpj=12345678000195&name=toolong"),
                422, "VALOR_INVALIDO", "name");
    }

    @Test
    void aMissingQueryParameterIs422WithItsName() {
        assertEnvelope(http().get("/test/web/validation/query"), 422, "VALOR_INVALIDO", "cnpj");
    }
}
