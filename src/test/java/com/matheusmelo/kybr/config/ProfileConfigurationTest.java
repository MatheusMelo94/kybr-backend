package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.matheusmelo.kybr.config.KybrProperties.Route;
import com.matheusmelo.kybr.config.KybrProperties.RouteLimit;
import com.matheusmelo.kybr.support.ProfileBoot;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.util.unit.DataSize;

/** The settings every profile must carry. */
class ProfileConfigurationTest {

    private static final String DOCUMENT_UPLOAD = "/public/v/{slug}/cases/{token}/documents";

    @ParameterizedTest
    @ValueSource(strings = {"local", "docker", "staging", "prod", "test"})
    void everyProfileHardensTheServer(String profile) {
        try (ConfigurableApplicationContext context = start(profile)) {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty("server.port")).isEqualTo("8080");
            assertThat(env.getProperty("server.forward-headers-strategy")).isEqualToIgnoringCase("none");
            assertThat(env.getProperty("server.max-http-request-header-size")).isEqualTo("16KB");
            assertThat(env.getProperty("server.error.whitelabel.enabled")).isEqualTo("false");
            assertThat(env.getProperty("server.error.include-message")).isEqualTo("never");
            assertThat(env.getProperty("server.error.include-binding-errors")).isEqualTo("never");
            assertThat(env.getProperty("server.error.include-stacktrace")).isEqualTo("never");
            assertThat(env.getProperty("server.error.include-path")).isEqualTo("never");
            assertThat(env.getProperty("server.error.include-exception")).isEqualTo("false");
            assertThat(env.getProperty("server.tomcat.accesslog.enabled")).isEqualTo("false");
            assertThat(env.getProperty("spring.web.resources.add-mappings")).isEqualTo("false");
            assertThat(env.getProperty("spring.servlet.multipart.enabled")).as("multipart is off").isEqualTo("false");
            assertThat(env.getProperty("spring.mvc.log-request-details")).isEqualTo("false");
            assertThat(env.getProperty("spring.data.mongodb.auto-index-creation")).isEqualTo("false");
            assertThat(env.getProperty("spring.threads.virtual.enabled")).isEqualTo("true");

            BindResult<Set<String>> trackingModes = Binder.get(env)
                    .bind("server.servlet.session.tracking-modes", Bindable.setOf(String.class));
            assertThat(trackingModes.isBound()).as("tracking modes explicitly set").isTrue();
            assertThat(trackingModes.get()).as("no session tracking at all").isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "docker", "staging", "prod", "test"})
    void everyProfileSplitsProbesAcrossPorts(String profile) {
        try (ConfigurableApplicationContext context = start(profile)) {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty("management.server.port")).isEqualTo("8081");
            assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
            assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
            assertThat(env.getProperty("management.endpoint.health.show-components")).isEqualTo("never");
            assertThat(env.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
            assertThat(env.getProperty("management.endpoint.health.probes.add-additional-paths"))
                    .as("would publish /readyz on the main port").isNotEqualTo("true");
            assertThat(env.getProperty("management.endpoint.health.group.liveness.additional-path"))
                    .isEqualTo("server:/livez");
            assertThat(env.getProperty("management.endpoint.health.group.readiness.additional-path"))
                    .as("readiness only on the management port").isNull();
            assertThat(env.getProperty("management.endpoint.health.group.readiness.include"))
                    .contains("readinessState").contains("mongo");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"staging", "prod"})
    void cloudProfilesLogEcsAndHideTheApiDocs(String profile) {
        try (ConfigurableApplicationContext context = start(profile)) {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("ecs");
            assertThat(env.getProperty("logging.level.org.springframework.web")).isEqualTo("INFO");
            assertThat(env.getProperty("logging.level.org.apache.catalina")).isEqualTo("INFO");
            assertThat(env.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
            assertThat(context.getBean(KybrProperties.class).clientIp().platformHeader()).isEqualTo("Fly-Client-IP");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test"})
    void devProfilesUsePlainLogsAndPublishTheApiDocs(String profile) {
        try (ConfigurableApplicationContext context = start(profile)) {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty("logging.structured.format.console")).isNull();
            assertThat(env.getProperty("springdoc.api-docs.enabled")).isEqualTo("true");
            assertThat(context.getBean(KybrProperties.class).clientIp().platformHeader()).isNull();
        }
    }

    @Test
    void dockerHidesTheApiDocs() {
        try (ConfigurableApplicationContext context = start("docker")) {
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "docker", "staging", "prod", "test"})
    void bffKeyIsDenyByDefaultWithOnlyLivezAndDocumentUploadExempt(String profile) {
        try (ConfigurableApplicationContext context = start(profile)) {
            KybrProperties properties = context.getBean(KybrProperties.class);

            assertThat(properties.bff().exemptions()).containsExactly(
                    new Route(HttpMethod.GET, "/livez"),
                    new Route(HttpMethod.POST, DOCUMENT_UPLOAD),
                    new Route(HttpMethod.OPTIONS, DOCUMENT_UPLOAD));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "docker", "staging", "prod", "test"})
    void bodyLimitsAndRedactionDefaults(String profile) {
        try (ConfigurableApplicationContext context = start(profile)) {
            KybrProperties properties = context.getBean(KybrProperties.class);

            assertThat(properties.limits().defaultMaxBody()).isEqualTo(DataSize.ofBytes(1024 * 1024));
            assertThat(properties.limits().routes()).isEmpty();
            assertThat(properties.limits().multipart())
                    .containsExactly(new RouteLimit(HttpMethod.POST, DOCUMENT_UPLOAD, DataSize.ofMegabytes(11)));
            assertThat(properties.logging().tokenPrefixes()).containsExactly("cases");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "docker", "test"})
    void devProfilesHaveNoSecretDefaults(String profile) {
        Throwable failure = catchThrowable(() -> ProfileBoot.start(profile,
                "--KYBR_BFF_KEY_CURRENT=", "--KYBR_HASH_SECRET=", "--KYBR_IDEMPOTENCY_ENC_KEY=").close());

        assertThat(failure).as("no committed key material, so no profile can start without secrets").isNotNull();
    }

    private static ConfigurableApplicationContext start(String profile) {
        return ProfileBoot.start(profile, ProfileBoot.validCloudEnv().toArray(String[]::new));
    }
}
