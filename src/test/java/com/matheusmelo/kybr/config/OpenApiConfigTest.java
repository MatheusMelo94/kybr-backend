package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.exception.ErrorCode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    private OpenAPI customised() {
        Operation operation = new Operation().responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("OK"))
                .addApiResponse("429", new ApiResponse().description("Too many")));
        OpenAPI openApi = config.kybrOpenApi().paths(new Paths().addPathItem("/v1/x", new PathItem().get(operation)));
        config.kybrConventions().customise(openApi);
        return openApi;
    }

    @Test
    void publishesTheContractVersionAndTheStaticServers() {
        OpenAPI openApi = customised();

        assertThat(openApi.getInfo().getVersion()).isEqualTo("0.4.0");
        assertThat(openApi.getServers()).extracting(Server::getUrl)
                .containsExactly("https://api.kybr.com.br", "https://api-staging.kybr.com.br");
    }

    @Test
    void definesTheErrorResponseSchemaWithEveryCode() {
        Schema<?> error = customised().getComponents().getSchemas().get("ErrorResponse");

        assertThat(error.getRequired()).containsExactly("code", "message");
        assertThat(error.getProperties()).containsOnlyKeys("code", "message", "field");
        assertThat(error.getProperties().get("code").getEnum()).map(Object::toString)
                .containsExactlyElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }

    @Test
    void everyResponseCarriesXRequestIdAnd429CarriesRetryAfter() {
        OpenAPI openApi = customised();
        ApiResponses responses = openApi.getPaths().get("/v1/x").getGet().getResponses();

        assertThat(openApi.getComponents().getHeaders()).containsKey("X-Request-Id");
        responses.forEach((status, response) -> assertThat(response.getHeaders().get("X-Request-Id").get$ref())
                .isEqualTo("#/components/headers/X-Request-Id"));
        assertThat(responses.get("429").getHeaders().get("Retry-After").getSchema().getType()).isEqualTo("integer");
        assertThat(responses.get("200").getHeaders()).doesNotContainKey("Retry-After");
        assertThat(responses.get("default").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ErrorResponse");
    }

    @Test
    void anErrorResponseGetsTheEnvelopeAndOneExamplePerNamedCodeIgnoringOtherBacktickedText() {
        Operation operation = new Operation().responses(new ApiResponses()
                .addApiResponse("404", new ApiResponse()
                        .description("- `LINK_NAO_ENCONTRADO`: no link at `/public/v/{slug}`.\n- `NOT_A_CODE`: x.")));
        OpenAPI openApi = config.kybrOpenApi().paths(new Paths().addPathItem("/v1/x", new PathItem().get(operation)));
        config.kybrConventions().customise(openApi);

        io.swagger.v3.oas.models.media.MediaType json = openApi.getPaths().get("/v1/x").getGet().getResponses()
                .get("404").getContent().get("application/json");
        assertThat(json.getSchema().get$ref()).isEqualTo("#/components/schemas/ErrorResponse");
        assertThat(json.getExamples()).containsOnlyKeys("LINK_NAO_ENCONTRADO");
        assertThat(json.getExamples().get("LINK_NAO_ENCONTRADO").getValue())
                .isEqualTo(java.util.Map.of("code", "LINK_NAO_ENCONTRADO", "message", "Link not found."));
    }
}
