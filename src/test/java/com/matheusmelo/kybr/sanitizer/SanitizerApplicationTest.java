package com.matheusmelo.kybr.sanitizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.support.upload.TestImages;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The sanitizer's own entry point: a context with nothing but the sanitizer, which refuses to start
 * when any data-store setting or Kybr secret is present.
 */
class SanitizerApplicationTest {

    /** An environment with the given variables and none of the test JVM's own (which carry test settings). */
    private static StandardEnvironment environment(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addLast(
                new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        return environment;
    }

    private static ConfigurableApplicationContext run(Map<String, Object> variables) {
        return SanitizerApplication.application(environment(variables)).run("--sanitizer.port=0");
    }

    @Test
    void startsWithOnlyTheSanitizerAndServesPhotos() throws Exception {
        try (ConfigurableApplicationContext context = run(Map.of("FLY_APP_NAME", "kybr-scan"))) {
            String[] beans = context.getBeanDefinitionNames();
            assertThat(Arrays.stream(beans).map(name -> context.getType(name)).filter(type -> type != null)
                    .map(Class::getName))
                    .noneMatch(type -> type.toLowerCase().matches(".*(mongo|s3|aws|email|resend|cnpja|provider|"
                            + "storage|scanner|datasource|security).*"))
                    .noneMatch(type -> type.startsWith("com.matheusmelo.kybr.")
                            && !type.startsWith("com.matheusmelo.kybr.sanitizer.")
                            && !type.equals("com.matheusmelo.kybr.service.image.ImageHeaderCheck"));

            SanitizerServer server = context.getBean(SanitizerServer.class);
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/sanitize"))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(TestImages.jpegWithMetadata(32, 24))).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(TestImages.hasExif(response.body())).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MONGODB_URI", "KYBR_HASH_SECRET", "KYBR_SCANNER_CLIENT_KEY", "KYBR_RESEND_API_KEY",
        "AWS_ROLE_ARN", "AWS_SECRET_ACCESS_KEY", "SPRING_DATA_MONGODB_URI", "spring.mongodb.uri", "kybr.pii.enc-key"})
    void refusesToStartWhenADataStoreSettingOrSecretIsPresent(String name) {
        assertThatThrownBy(() -> run(Map.of(name, "x")).close())
                .hasStackTraceContaining("must not be set")
                .hasStackTraceContaining(name);
    }

    @Test
    void neverLoadsTheApiConfiguration() {
        try (ConfigurableApplicationContext context = run(Map.of())) {
            assertThat(context.getEnvironment().getProperty("kybr.upload.max-files-per-case")).isNull();
            assertThat(context.getEnvironment().getProperty("spring.mongodb.uri")).isNull();
        }
    }
}
