package com.matheusmelo.kybr.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.exception.ErrorCode;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The committed contract of record,
 * {@code docs/openapi.yaml}, read as a client would. {@code OpenApiDriftIT} proves the file equals
 * what the app generates; this proves the file says what B0 promises.
 */
@Tag("b0-foundation")
class CommittedContractAcceptanceTest {

    private static Map<String, Object> spec() throws IOException {
        try (Reader reader = Files.newBufferedReader(Path.of("docs", "openapi.yaml"), StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCommittedSpecIsContract010WithTheEnvelopeAndNoPaths() throws IOException {
        Map<String, Object> spec = spec();
        Map<String, Object> info = (Map<String, Object>) spec.get("info");
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> envelope = (Map<String, Object>) schemas.get("ErrorResponse");
        Map<String, Object> properties = (Map<String, Object>) envelope.get("properties");
        Map<String, Object> code = (Map<String, Object>) properties.get("code");

        assertThat(info).containsEntry("version", "0.1.0");
        assertThat((Map<String, Object>) spec.get("paths")).as("0.1.0 publishes no paths").isEmpty();
        assertThat((List<Map<String, Object>>) spec.get("servers")).extracting(s -> s.get("url"))
                .containsExactly("https://api.kybr.com.br", "https://api-staging.kybr.com.br");
        assertThat(properties.keySet()).containsExactlyInAnyOrder("code", "message", "field");
        assertThat((List<String>) envelope.get("required")).containsExactlyInAnyOrder("code", "message");
        assertThat((List<String>) code.get("enum")).as("ErrorResponse.code.enum = ErrorCode")
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        assertThat((Map<String, Object>) components.get("headers")).containsKey("X-Request-Id");
    }

    @Test
    void theCommittedSpecExposesNoProbeActuatorOrTestRoute() throws IOException {
        String raw = Files.readString(Path.of("docs", "openapi.yaml"), StandardCharsets.UTF_8);

        assertThat(raw).doesNotContain("/livez", "/readyz", "/actuator", "/test/", "localhost");
    }
}
