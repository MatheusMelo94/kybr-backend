package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The generated contract must equal the committed {@code docs/openapi.yaml}.
 * {@code ./mvnw verify -Dopenapi.write=true} rewrites the file instead of comparing.
 */
class OpenApiDriftIT extends WebIntegrationTest {

    private static final Path COMMITTED = Path.of("docs", "openapi.yaml");

    @Test
    void theGeneratedSpecMatchesTheCommittedFile() throws IOException {
        Response response = http().get("/v3/api-docs.yaml");
        assertThat(response.statusCode()).isEqualTo(200);
        String generated = new String(response.asByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");

        if (Boolean.getBoolean("openapi.write")) {
            Files.writeString(COMMITTED, generated, StandardCharsets.UTF_8);
        }

        assertThat(COMMITTED)
                .as("docs/openapi.yaml is out of date; regenerate with ./mvnw verify -Dopenapi.write=true")
                .exists()
                .usingCharset(StandardCharsets.UTF_8)
                .hasContent(generated);
    }
}
