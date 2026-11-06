package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OpenApiConfigIT extends WebIntegrationTest {

    private JsonPath docs() {
        Response response = http().get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        return response.jsonPath();
    }

    @Test
    void theYamlCarriesTheContractVersionTheErrorSchemaAndTheRequestIdHeader() {
        String yaml = http().get("/v3/api-docs.yaml").asString();

        assertThat(yaml).contains("version: 0.1.0").contains("ErrorResponse:").contains("X-Request-Id:");
    }

    @Test
    void theErrorResponseCodeEnumIsEveryErrorCode() {
        assertThat(docs().getList("components.schemas.ErrorResponse.properties.code.enum", String.class))
                .containsExactlyElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }

    /**
     * B0's contract has no paths: actuator, {@code /livez}, {@code /error} and the
     * test-only probe controllers are all excluded. Per-operation decoration is unit-tested in
     * {@link OpenApiConfigTest}.
     */
    @Test
    void theContractHasNoPathsSoActuatorProbesAndTestControllersAreExcluded() {
        assertThat(docs().getMap("paths")).isNullOrEmpty();
        assertThat(http().get("/v3/api-docs.yaml").asString()).doesNotContain("/test/").doesNotContain("/public/");
    }

    @Test
    void theOutputIsStaticAcrossRequestsAndCarriesNoHostOrPort() {
        String first = http().get("/v3/api-docs.yaml").asString();
        String second = http().get("/v3/api-docs.yaml").asString();

        assertThat(first).isEqualTo(second).doesNotContain("localhost").doesNotContain(":" + port);
        assertThat(docs().getList("servers.url", String.class))
                .containsExactly("https://api.kybr.com.br", "https://api-staging.kybr.com.br");
    }
}
