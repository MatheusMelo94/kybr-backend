package com.matheusmelo.kybr.config;

import com.matheusmelo.kybr.exception.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI contract of record. {@code info.version} is the <b>contract</b>
 * version (SemVer, separate from the app version); {@code servers} is a static list, never derived
 * from the request, so the generated document is the same on every port.
 *
 * <p>Every operation gets the {@code X-Request-Id} response header on every response, a
 * {@code default} response with the {@code ErrorResponse} envelope ({@code code} is the
 * closed {@link ErrorCode} enum), and {@code Retry-After} (delta-seconds) on any 429. Actuator
 * endpoints are not documented (springdoc's default), and the docs are served in local and test
 * only.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** The contract version: bump it (SemVer) on every contract change. */
    public static final String CONTRACT_VERSION = "0.1.0";
    public static final List<String> SERVERS = List.of("https://api.kybr.com.br", "https://api-staging.kybr.com.br");

    private static final String REQUEST_ID = "X-Request-Id";
    private static final String ERROR_RESPONSE = "ErrorResponse";

    @Bean
    OpenAPI kybrOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Kybr API").version(CONTRACT_VERSION))
                .servers(SERVERS.stream().map(url -> new Server().url(url)).toList());
    }

    @Bean
    OpenApiCustomizer kybrConventions() {
        return openApi -> {
            Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
            openApi.components(components
                    .addSchemas(ERROR_RESPONSE, errorResponseSchema())
                    .addHeaders(REQUEST_ID, new Header()
                            .description("A fresh UUID v4 for this request, for support and log correlation.")
                            .schema(new StringSchema().format("uuid"))));
            openApi.servers(SERVERS.stream().map(url -> new Server().url(url)).toList());
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                ApiResponses responses = operation.getResponses() == null ? new ApiResponses()
                        : operation.getResponses();
                if (!responses.containsKey("default")) {
                    responses.addApiResponse("default", new ApiResponse()
                            .description("Error envelope")
                            .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                                    new MediaType().schema(new Schema<>().$ref(ref("schemas", ERROR_RESPONSE))))));
                }
                responses.forEach((status, response) -> decorate(status, response));
                operation.responses(responses);
            }));
        };
    }

    private static void decorate(String status, ApiResponse response) {
        Map<String, Header> headers = new TreeMap<>(response.getHeaders() == null ? Map.of() : response.getHeaders());
        headers.put(REQUEST_ID, new Header().$ref(ref("headers", REQUEST_ID)));
        if ("429".equals(status)) {
            headers.put("Retry-After", new Header()
                    .description("Seconds to wait before retrying (digits only).")
                    .schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE)));
        }
        response.headers(headers);
    }

    private static Schema<?> errorResponseSchema() {
        StringSchema code = new StringSchema();
        code.setEnum(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        Schema<?> schema = new ObjectSchema()
                .description("The error envelope. `message` is fixed per code; clients branch on `code`.")
                .addProperty("code", code.description("The error code."))
                .addProperty("message", new StringSchema().description("A fixed, generic message for the code."))
                .addProperty("field", new StringSchema().description("The offending field or header, when known."));
        schema.setRequired(List.of("code", "message"));
        return schema;
    }

    private static String ref(String section, String name) {
        return "#/components/" + section + "/" + name;
    }
}
