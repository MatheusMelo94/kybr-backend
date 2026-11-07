package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.matheusmelo.kybr.config.KybrProperties.LimitWindow;
import com.matheusmelo.kybr.config.KybrProperties.Route;
import com.matheusmelo.kybr.config.KybrProperties.RouteLimit;
import com.matheusmelo.kybr.support.ProfileBoot;
import java.time.Duration;
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
            assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus,admin");
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
    @ValueSource(strings = {"local", "docker", "staging", "prod", "test"})
    void everyProfileCarriesTheSellerLinkDefaults(String profile) {
        try (ConfigurableApplicationContext context = start(profile)) {
            KybrProperties properties = context.getBean(KybrProperties.class);
            KybrProperties.LinkLimits limits = properties.link().limits();

            assertThat(limits.lookupIp()).isEqualTo(new LimitWindow(5, Duration.ofMinutes(1)));
            assertThat(limits.lookupIpDaily()).isEqualTo(new LimitWindow(30, Duration.ofDays(1)));
            assertThat(limits.lookupIpUnknown()).isEqualTo(new LimitWindow(120, Duration.ofMinutes(1)));
            assertThat(limits.verifyIp()).isEqualTo(new LimitWindow(10, Duration.ofMinutes(10)));
            assertThat(limits.verifyIpUnknown()).isEqualTo(new LimitWindow(300, Duration.ofMinutes(10)));
            assertThat(limits.verifyCnpj()).isEqualTo(new LimitWindow(3, Duration.ofHours(1)));
            assertThat(limits.caseIp()).isEqualTo(new LimitWindow(300, Duration.ofMinutes(1)));
            assertThat(limits.caseIpUnknown()).isEqualTo(new LimitWindow(1200, Duration.ofMinutes(1)));
            assertThat(limits.contactIp()).isEqualTo(new LimitWindow(20, Duration.ofHours(1)));
            assertThat(limits.contactIpUnknown()).isEqualTo(new LimitWindow(200, Duration.ofHours(1)));
            assertThat(limits.contactCase()).isEqualTo(new LimitWindow(5, Duration.ofDays(1)));

            KybrProperties.Budgets budgets = properties.link().budgets();
            assertThat(budgets.d2Share()).isEqualTo(0.20);
            assertThat(budgets.burst()).isEqualTo(2.0);
            assertThat(budgets.lookupPerSlugDaily()).isEqualTo(50);
            assertThat(budgets.verifyPerSlugDaily()).isEqualTo(30);
            assertThat(budgets.noDataPerSlugDaily()).isEqualTo(20);
            assertThat(budgets.cacheReadsPerSlugAlert()).isEqualTo(500);
            assertThat(properties.link().reviewDeadline()).isEqualTo(Duration.ofDays(2));
            assertThat(properties.link().regularizacao().receitaUrl().getScheme()).isEqualTo("https");

            assertThat(properties.cache().positiveTtl()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.cache().negativeTtl()).isEqualTo(Duration.ofDays(1));
            assertThat(properties.verification().workers()).isEqualTo(4);
            assertThat(properties.verification().queue()).isEqualTo(100);
            assertThat(properties.verification().maxAttempts()).isEqualTo(3);
            assertThat(properties.verification().backoff())
                    .containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2));
            assertThat(properties.verification().lease()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.verification().pollInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.runtimeFlags().cacheTtl()).isEqualTo(Duration.ofSeconds(10));

            KybrProperties.Cnpja cnpja = properties.cnpja();
            assertThat(cnpja.timeouts().connect()).isEqualTo(Duration.ofSeconds(2));
            assertThat(cnpja.timeouts().lookupRead()).isEqualTo(Duration.ofSeconds(3));
            assertThat(cnpja.timeouts().workerRead()).isEqualTo(Duration.ofSeconds(8));
            assertThat(cnpja.creditCosts()).isEqualTo(new KybrProperties.CreditCosts(1, 1, 1));
            assertThat(cnpja.baseUrl().getScheme()).isEqualTo("https");
            assertThat(cnpja.authHeader()).isEqualTo("Authorization");
            assertThat(cnpja.addons()).isEqualTo(new KybrProperties.Cnpja.Addons(true, "BR"));

            assertThat(properties.metrics().maxSlugTags()).isEqualTo(500);
            assertThat(properties.admin().maxBody()).isEqualTo(DataSize.ofKilobytes(16));
            assertThat(properties.clientIp().notExpected()).containsExactlyInAnyOrder(
                    new Route(HttpMethod.GET, "/public/v/{slug}"),
                    new Route(HttpMethod.GET, "/livez"),
                    new Route(HttpMethod.POST, DOCUMENT_UPLOAD),
                    new Route(HttpMethod.OPTIONS, DOCUMENT_UPLOAD));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "docker", "staging", "test"})
    void nonProdProfilesUseTheStubProviderAndAllowExplicitSlugs(String profile) {
        try (ConfigurableApplicationContext context = start(profile)) {
            KybrProperties properties = context.getBean(KybrProperties.class);

            assertThat(properties.cnpja().mode()).isEqualTo(KybrProperties.Cnpja.Mode.STUB);
            assertThat(properties.admin().allowExplicitSlug()).isTrue();
            assertThat(properties.verification().pollerEnabled()).as("the poller is off only in tests")
                    .isEqualTo(!"test".equals(profile));
        }
    }

    @Test
    void prodUsesTheLiveProviderAndNeverExplicitSlugs() {
        try (ConfigurableApplicationContext context = start("prod")) {
            KybrProperties properties = context.getBean(KybrProperties.class);

            assertThat(properties.cnpja().mode()).isEqualTo(KybrProperties.Cnpja.Mode.LIVE);
            assertThat(properties.admin().allowExplicitSlug()).isFalse();
            assertThat(properties.verification().pollerEnabled()).isTrue();
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
