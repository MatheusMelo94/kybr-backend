package com.matheusmelo.kybr.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.matheusmelo.kybr.exception.ErrorCode;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * closed {@link ErrorCode} enum), and {@code Retry-After} (delta-seconds) on any 429. A 4xx or 5xx
 * response gets the envelope as its content and one example per code its description names in
 * backticks. The link's request headers ({@code X-Kybr-BFF-Key}, {@code X-Kybr-Client-IP}) are
 * reusable parameter components that operations reference. Actuator
 * endpoints are not documented (springdoc's default), and the docs are served in local and test
 * only.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** The contract version: bump it (SemVer) on every contract change. */
    public static final String CONTRACT_VERSION = "0.2.0";
    public static final List<String> SERVERS = List.of("https://api.kybr.com.br", "https://api-staging.kybr.com.br");

    private static final String REQUEST_ID = "X-Request-Id";
    private static final String ERROR_RESPONSE = "ErrorResponse";
    private static final String BFF_KEY = "X-Kybr-BFF-Key";
    private static final String CLIENT_IP = "X-Kybr-Client-IP";
    private static final String JSON = org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
    /** An error code named in a response description: {@code `LINK_NAO_ENCONTRADO`}. */
    private static final Pattern NAMED_CODE = Pattern.compile("`([A-Z_]+)`");

    static final String DESCRIPTION = """
            The seller link lives under the unversioned base path `/public/v/{slug}`. A breaking change \
            would ship as `/public/v2/{slug}/...` next to it, never in place.

            Every call comes from the link's server with `X-Kybr-BFF-Key`; calls that are rate-limited \
            or audited also carry `X-Kybr-Client-IP`. Errors use the `ErrorResponse` envelope: branch on \
            `code`, never on `message`. Every response carries `X-Request-Id`, and every 429 carries \
            `Retry-After` in seconds. Timestamps are ISO 8601 with an offset. CNPJs are 14 upper-case \
            characters without punctuation, numeric or alphanumeric.""";

    @Bean
    OpenAPI kybrOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Kybr API").version(CONTRACT_VERSION).description(DESCRIPTION))
                .servers(SERVERS.stream().map(url -> new Server().url(url)).toList());
    }

    /**
     * Schema property names in snake_case, as on the wire. The contract generator reads models
     * with its own mapper, not the application's, so the naming rule is repeated here.
     */
    @Bean
    ModelResolver kybrSnakeCaseModels() {
        return new ModelResolver(Json.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }

    @Bean
    OpenApiCustomizer kybrConventions() {
        return openApi -> {
            Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
            openApi.components(components
                    .addSchemas(ERROR_RESPONSE, errorResponseSchema())
                    .addHeaders(REQUEST_ID, new Header()
                            .description("A fresh UUID v4 for this request, for support and log correlation.")
                            .schema(new StringSchema().format("uuid")))
                    .addParameters(BFF_KEY, new Parameter().in("header").name(BFF_KEY).required(true)
                            .description("The server-to-server key of the link's frontend. Required on every "
                                    + "call; it grants trust in `X-Kybr-Client-IP` and nothing else. Never logged.")
                            .schema(new StringSchema()))
                    .addParameters(CLIENT_IP, new Parameter().in("header").name(CLIENT_IP).required(false)
                            .description("The end user's IP address (IPv4 or IPv6, no port), trusted only with a "
                                    + "valid `X-Kybr-BFF-Key`. Omit it when it can't be derived; never send an "
                                    + "empty or guessed value. Without it, limits are counted per link.")
                            .schema(new StringSchema())));
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
        if (status.matches("[45]\\d\\d")) {
            describeErrors(response);
        }
        Map<String, Header> headers = new TreeMap<>(response.getHeaders() == null ? Map.of() : response.getHeaders());
        headers.put(REQUEST_ID, new Header().$ref(ref("headers", REQUEST_ID)));
        if ("429".equals(status)) {
            headers.put("Retry-After", new Header()
                    .description("Seconds to wait before retrying (digits only).")
                    .schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE)));
        }
        response.headers(headers);
    }

    /**
     * An error response gets the envelope as its content and one example per code its description
     * names, each with that code's fixed message.
     */
    private static void describeErrors(ApiResponse response) {
        Map<String, Example> examples = new LinkedHashMap<>();
        Matcher matcher = NAMED_CODE.matcher(response.getDescription() == null ? "" : response.getDescription());
        while (matcher.find()) {
            Arrays.stream(ErrorCode.values()).filter(code -> code.name().equals(matcher.group(1))).findFirst()
                    .ifPresent(code -> examples.putIfAbsent(code.name(), new Example()
                            .value(envelope(code))));
        }
        MediaType content = new MediaType().schema(new Schema<>().$ref(ref("schemas", ERROR_RESPONSE)));
        if (!examples.isEmpty()) {
            content.examples(examples);
        }
        response.content(new Content().addMediaType(JSON, content));
    }

    /** The envelope for {@code code}, in wire order (a stable contract file). */
    private static Map<String, String> envelope(ErrorCode code) {
        Map<String, String> envelope = new LinkedHashMap<>();
        envelope.put("code", code.name());
        envelope.put("message", code.message());
        return envelope;
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
