package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.matheusmelo.kybr.support.ProfileBoot;
import com.matheusmelo.kybr.support.TestSecrets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ConfigurableApplicationContext;

/** Staging and prod refuse to start without valid secrets or with unsafe settings. */
class KybrPropertiesStartupTest {

    private static final String NOT_BASE64URL = "not+base64/url+value+with+enough+characters+x";
    private static final String DECODES_TO_31_BYTES = TestSecrets.base64Url(31, 9);
    private static final String SHORT_BASE64 = Base64.getEncoder().encodeToString(new byte[16]);
    private static final String NOT_PEM = TestSecrets.base64Url(48, 7);

    @Test
    void prodStartsWhenEverySecretIsValid() {
        List<String> env = ProfileBoot.validCloudEnv();
        env.add("--KYBR_BFF_KEY_PREVIOUS=" + TestSecrets.BFF_KEY_PREVIOUS);
        env.add("--KYBR_HASH_SECRET_PREVIOUS=" + TestSecrets.HASH_SECRET_PREVIOUS);

        try (ConfigurableApplicationContext context = ProfileBoot.start("prod", env.toArray(String[]::new))) {
            KybrProperties properties = context.getBean(KybrProperties.class);
            assertThat(properties.bff().current()).isEqualTo(TestSecrets.BFF_KEY_CURRENT);
            assertThat(properties.bff().previous()).isEqualTo(TestSecrets.BFF_KEY_PREVIOUS);
            assertThat(properties.hashing().secret()).isEqualTo(TestSecrets.HASH_SECRET);
            assertThat(properties.hashing().previous()).isEqualTo(TestSecrets.HASH_SECRET_PREVIOUS);
            assertThat(properties.idempotency().encKey()).isEqualTo(TestSecrets.IDEMPOTENCY_ENC_KEY);
        }
    }

    @Test
    void prodStartsWithoutTheOptionalPreviousSecrets() {
        try (ConfigurableApplicationContext context =
                ProfileBoot.start("prod", ProfileBoot.validCloudEnv().toArray(String[]::new))) {
            KybrProperties properties = context.getBean(KybrProperties.class);
            assertThat(properties.bff().previous()).isNull();
            assertThat(properties.hashing().previous()).isNull();
        }
    }

    static Stream<Arguments> invalidSecrets() {
        return Stream.of(
                Arguments.of("BFF key missing", "KYBR_BFF_KEY_CURRENT", "", "kybr.bff.current"),
                Arguments.of("BFF key not base64url", "KYBR_BFF_KEY_CURRENT", NOT_BASE64URL, "kybr.bff.current"),
                Arguments.of("BFF key < 32 bytes", "KYBR_BFF_KEY_CURRENT", DECODES_TO_31_BYTES, "kybr.bff.current"),
                Arguments.of("BFF previous equals current", "KYBR_BFF_KEY_PREVIOUS", TestSecrets.BFF_KEY_CURRENT,
                        "kybr.bff.previous"),
                Arguments.of("BFF previous not base64url", "KYBR_BFF_KEY_PREVIOUS", NOT_BASE64URL,
                        "kybr.bff.previous"),
                Arguments.of("BFF previous < 32 bytes", "KYBR_BFF_KEY_PREVIOUS", DECODES_TO_31_BYTES,
                        "kybr.bff.previous"),
                Arguments.of("hash secret missing", "KYBR_HASH_SECRET", "", "kybr.hashing.secret"),
                Arguments.of("hash secret not base64url", "KYBR_HASH_SECRET", NOT_BASE64URL, "kybr.hashing.secret"),
                Arguments.of("hash secret < 32 bytes", "KYBR_HASH_SECRET", DECODES_TO_31_BYTES, "kybr.hashing.secret"),
                Arguments.of("hash previous equals secret", "KYBR_HASH_SECRET_PREVIOUS", TestSecrets.HASH_SECRET,
                        "kybr.hashing.previous"),
                Arguments.of("hash previous < 32 bytes", "KYBR_HASH_SECRET_PREVIOUS", DECODES_TO_31_BYTES,
                        "kybr.hashing.previous"),
                Arguments.of("idempotency key missing", "KYBR_IDEMPOTENCY_ENC_KEY", "", "kybr.idempotency.enc-key"),
                Arguments.of("PII keyset missing", "KYBR_PII_ENC_KEY", "", "kybr.pii.enc-key"),
                Arguments.of("PII keyset too short", "KYBR_PII_ENC_KEY", SHORT_BASE64, "kybr.pii.enc-key"),
                Arguments.of("PII keyset reuses the replay keyset", "KYBR_PII_ENC_KEY", TestSecrets.IDEMPOTENCY_ENC_KEY,
                        "kybr.pii.enc-key"),
                Arguments.of("admin token missing", "KYBR_ADMIN_TOKEN", "", "kybr.admin.token"),
                Arguments.of("admin token < 32 bytes", "KYBR_ADMIN_TOKEN", DECODES_TO_31_BYTES, "kybr.admin.token"),
                Arguments.of("admin previous equals current", "KYBR_ADMIN_TOKEN_PREVIOUS", TestSecrets.ADMIN_TOKEN,
                        "kybr.admin.token-previous"),
                Arguments.of("live provider without an API key", "KYBR_CNPJA_API_KEY", "", "kybr.cnpja.api-key"),
                Arguments.of("live provider without monthly credits", "KYBR_CNPJA_MONTHLY_CREDITS", "",
                        "kybr.cnpja.monthly-credits"),
                Arguments.of("scanner without a host", "KYBR_SCANNER_HOST", "", "kybr.scanner.host"),
                Arguments.of("scanner without a pin", "KYBR_SCANNER_SPKI_SHA256", "", "kybr.scanner.spki-sha256"),
                Arguments.of("scanner pin not a SHA-256", "KYBR_SCANNER_SPKI_SHA256", SHORT_BASE64,
                        "kybr.scanner.spki-sha256"),
                Arguments.of("scanner without a client certificate", "KYBR_SCANNER_CLIENT_CERT", "",
                        "kybr.scanner.client-cert"),
                Arguments.of("scanner client certificate not PEM", "KYBR_SCANNER_CLIENT_CERT", NOT_PEM,
                        "kybr.scanner.client-cert"),
                Arguments.of("scanner without a client key", "KYBR_SCANNER_CLIENT_KEY", "", "kybr.scanner.client-key"),
                Arguments.of("scanner client key not PEM", "KYBR_SCANNER_CLIENT_KEY", NOT_PEM,
                        "kybr.scanner.client-key"),
                Arguments.of("scanner client key is a certificate", "KYBR_SCANNER_CLIENT_KEY",
                        TestSecrets.SCANNER_CLIENT_CERT, "kybr.scanner.client-key"),
                Arguments.of("scanner without a CA", "KYBR_SCANNER_CA", "", "kybr.scanner.ca"),
                Arguments.of("sanitizer without a host", "KYBR_SANITIZER_HOST", "", "kybr.sanitizer.host"),
                Arguments.of("sanitizer without a port", "KYBR_SANITIZER_PORT", "", "kybr.sanitizer.port"),
                Arguments.of("storage without a bucket", "KYBR_STORAGE_BUCKET", "", "kybr.storage.bucket"),
                Arguments.of("storage without a KMS key", "KYBR_STORAGE_KMS_KEY_ID", "", "kybr.storage.kms-key-id"),
                Arguments.of("storage without a quarantine bucket", "KYBR_STORAGE_QUARANTINE_BUCKET", "",
                        "kybr.storage.quarantine-bucket"),
                Arguments.of("storage without a quarantine KMS key", "KYBR_STORAGE_QUARANTINE_KMS_KEY_ID", "",
                        "kybr.storage.quarantine-kms-key-id"),
                Arguments.of("a quarantine sharing the final bucket", "KYBR_STORAGE_QUARANTINE_BUCKET",
                        "kybr-documents-test", "kybr.storage.quarantine-bucket"),
                Arguments.of("storage without credentials", "KYBR_AWS_ROLE_ARN", "", "kybr.storage.role-arn"),
                Arguments.of("e-mail provider without a key", "KYBR_RESEND_API_KEY", "", "kybr.email.api-key"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSecrets")
    void prodFailsToStart(String description, String variable, String value, String property) {
        List<String> env = new ArrayList<>(ProfileBoot.validCloudEnv());
        env.removeIf(arg -> arg.startsWith("--" + variable + "="));
        env.add("--" + variable + "=" + value);

        Throwable failure = catchThrowable(() -> ProfileBoot.start("prod", env.toArray(String[]::new)).close());

        assertThat(failure).as(description).isNotNull();
        String messages = allMessages(failure);
        assertThat(messages).contains(property);
        if (!value.isEmpty()) {
            assertThat(messages).as("the secret value is never echoed").doesNotContain(value);
        }
    }

    static Stream<Arguments> unsafeSettings() {
        return Stream.of(
                Arguments.of("kybr.link.budgets.d2-share=0", "kybr.link.budgets.d2-share"),
                Arguments.of("kybr.link.budgets.d2-share=1", "kybr.link.budgets.d2-share"),
                Arguments.of("kybr.link.budgets.d2-share=1.5", "kybr.link.budgets.d2-share"),
                Arguments.of("kybr.link.budgets.burst=0", "kybr.link.budgets.burst"),
                Arguments.of("kybr.link.regularizacao.receita-url=http://www.gov.br/x",
                        "kybr.link.regularizacao.receita-url"),
                Arguments.of("kybr.link.regularizacao.receita-url=https://gov.br.example.com/x",
                        "kybr.link.regularizacao.receita-url"),
                Arguments.of("kybr.link.regularizacao.receita-url=https://example.com/www.gov.br",
                        "kybr.link.regularizacao.receita-url"),
                Arguments.of("kybr.link.regularizacao.receita-url=https://someone@www.gov.br/x",
                        "kybr.link.regularizacao.receita-url"),
                Arguments.of("kybr.link.regularizacao.receita-url=https://notgov.br/x",
                        "kybr.link.regularizacao.receita-url"),
                Arguments.of("kybr.admin.allow-explicit-slug=true", "kybr.admin.allow-explicit-slug"),
                Arguments.of("kybr.verification.poller-enabled=false", "kybr.verification.poller-enabled"),
                Arguments.of("kybr.cnpja.mode=stub", "kybr.cnpja.mode"),
                Arguments.of("kybr.scanner.mode=stub", "kybr.scanner.mode"),
                Arguments.of("kybr.storage.mode=memory", "kybr.storage.mode"),
                Arguments.of("kybr.email.mode=recording", "kybr.email.mode"),
                Arguments.of("kybr.image.mode=in-process-test", "kybr.image.mode"),
                Arguments.of("kybr.email.dispatcher.enabled=false", "kybr.email.dispatcher.enabled"),
                Arguments.of("kybr.retention.enabled=false", "kybr.retention.enabled"),
                Arguments.of("kybr.scan.enabled=false", "kybr.scan.enabled"),
                Arguments.of("kybr.scan.wake-timeout=121s", "kybr.scan.wake-timeout"),
                Arguments.of("kybr.cors.d6.origin-patterns=^https://preview\\.example\\.test$",
                        "kybr.cors.d6.origin-patterns"),
                Arguments.of("kybr.cors.d6.origins=http://kybr.com.br", "kybr.cors.d6.origins"),
                Arguments.of("kybr.cors.d6.origins=https://kybr.com.br/", "kybr.cors.d6.origins"),
                Arguments.of("kybr.cors.d6.origins=*", "kybr.cors.d6.origins"),
                Arguments.of("kybr.storage.access-key-id=" + TestSecrets.AWS_ACCESS_KEY_ID,
                        "kybr.storage.secret-access-key"),
                Arguments.of("kybr.image.target-long-edge=13000", "kybr.image.target-long-edge"),
                Arguments.of("kybr.upload.temp-dir=relative/uploads", "kybr.upload.temp-dir"),
                Arguments.of("kybr.storage.endpoint=http://127.0.0.1:9000", "kybr.storage.endpoint"),
                Arguments.of("kybr.upload.temp-dir=" + System.getProperty("java.io.tmpdir"), "kybr.upload.temp-dir"),
                Arguments.of("kybr.upload.temp-dir=" + System.getProperty("java.io.tmpdir") + "/x/..",
                        "kybr.upload.temp-dir"),
                Arguments.of("kybr.upload.temp-dir=" + System.getProperty("user.home"), "kybr.upload.temp-dir"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeSettings")
    void prodRefusesAnUnsafeSetting(String setting, String property) {
        List<String> env = new ArrayList<>(ProfileBoot.validCloudEnv());
        env.add("--" + setting);

        Throwable failure = catchThrowable(() -> ProfileBoot.start("prod", env.toArray(String[]::new)).close());

        assertThat(failure).as(setting).isNotNull();
        assertThat(allMessages(failure)).contains(property);
    }

    @Test
    void prodRunsTheLiveProviderWithItsKeyAndCredits() {
        try (ConfigurableApplicationContext context =
                ProfileBoot.start("prod", ProfileBoot.validCloudEnv().toArray(String[]::new))) {
            KybrProperties properties = context.getBean(KybrProperties.class);
            assertThat(properties.cnpja().mode()).isEqualTo(KybrProperties.Cnpja.Mode.LIVE);
            assertThat(properties.cnpja().apiKey()).isEqualTo(TestSecrets.CNPJA_API_KEY);
            assertThat(properties.pii().encKey()).isEqualTo(TestSecrets.PII_ENC_KEY);
            assertThat(properties.admin().token()).isEqualTo(TestSecrets.ADMIN_TOKEN);
            assertThat(properties.admin().allowExplicitSlug()).isFalse();
        }
    }

    @Test
    void theStubProviderNeedsNoApiKeyOrCredits() {
        List<String> env = new ArrayList<>(ProfileBoot.validCloudEnv());
        env.removeIf(arg -> arg.startsWith("--KYBR_CNPJA_"));
        env.add("--KYBR_CNPJA_MODE=stub");

        try (ConfigurableApplicationContext context = ProfileBoot.start("staging", env.toArray(String[]::new))) {
            assertThat(context.getBean(KybrProperties.class).cnpja().apiKey()).isNull();
        }
    }

    @Test
    void prodAcceptsStaticStorageKeysInPlaceOfTheRole() {
        List<String> env = new ArrayList<>(ProfileBoot.validCloudEnv());
        env.removeIf(arg -> arg.startsWith("--KYBR_AWS_ROLE_ARN="));
        env.add("--KYBR_AWS_ACCESS_KEY_ID=" + TestSecrets.AWS_ACCESS_KEY_ID);
        env.add("--KYBR_AWS_SECRET_ACCESS_KEY=" + TestSecrets.AWS_SECRET_ACCESS_KEY);

        try (ConfigurableApplicationContext context = ProfileBoot.start("prod", env.toArray(String[]::new))) {
            KybrProperties.Storage storage = context.getBean(KybrProperties.class).storage();
            assertThat(storage.roleArn()).isNull();
            assertThat(storage.accessKeyId()).isEqualTo(TestSecrets.AWS_ACCESS_KEY_ID);
        }
    }

    @Test
    void theSettingsTextFormsNeverCarryTheNewSecrets() {
        List<String> env = new ArrayList<>(ProfileBoot.validCloudEnv());
        env.add("--KYBR_AWS_ACCESS_KEY_ID=" + TestSecrets.AWS_ACCESS_KEY_ID);
        env.add("--KYBR_AWS_SECRET_ACCESS_KEY=" + TestSecrets.AWS_SECRET_ACCESS_KEY);

        try (ConfigurableApplicationContext context = ProfileBoot.start("prod", env.toArray(String[]::new))) {
            KybrProperties properties = context.getBean(KybrProperties.class);
            assertThat(properties.scanner().toString()).doesNotContain(TestSecrets.SCANNER_CLIENT_KEY);
            assertThat(properties.storage().toString()).doesNotContain(TestSecrets.AWS_SECRET_ACCESS_KEY)
                    .doesNotContain(TestSecrets.AWS_ACCESS_KEY_ID);
            assertThat(properties.email().toString()).doesNotContain(TestSecrets.RESEND_API_KEY);
        }
    }

    @Test
    void stagingRunsWithoutAnyScannerStorageOrEmailSettings() {
        List<String> env = new ArrayList<>(ProfileBoot.validCloudEnv());
        env.removeIf(arg -> arg.startsWith("--KYBR_SCANNER_") || arg.startsWith("--KYBR_SANITIZER_")
                || arg.startsWith("--KYBR_STORAGE_") || arg.startsWith("--KYBR_AWS_") || arg.startsWith("--KYBR_RESEND_"));

        try (ConfigurableApplicationContext context = ProfileBoot.start("staging", env.toArray(String[]::new))) {
            KybrProperties properties = context.getBean(KybrProperties.class);
            assertThat(properties.scanner().mode()).isEqualTo(KybrProperties.Scanner.Mode.STUB);
            assertThat(properties.storage().mode()).isEqualTo(KybrProperties.Storage.Mode.MEMORY);
            assertThat(properties.email().mode()).isEqualTo(KybrProperties.Email.Mode.RECORDING);
            assertThat(properties.email().apiKey()).isNull();
        }
    }

    @Test
    void theLiveSenderRefusesAnEnabledNoticeWhileTheDomainIsUnverified() {
        List<String> env = new ArrayList<>(ProfileBoot.validCloudEnv());
        env.add("--kybr.email.mode=resend");
        env.add("--kybr.email.dns-verified=false");
        env.add("--kybr.email.events.contato-alterado.enabled=true");

        Throwable failure = catchThrowable(() -> ProfileBoot.start("staging", env.toArray(String[]::new)).close());

        assertThat(failure).isNotNull();
        assertThat(allMessages(failure)).contains("kybr.email.dns-verified");
    }

    @Test
    void stagingMayAllowExplicitSlugs() {
        try (ConfigurableApplicationContext context =
                ProfileBoot.start("staging", ProfileBoot.validCloudEnv().toArray(String[]::new))) {
            assertThat(context.getBean(KybrProperties.class).admin().allowExplicitSlug()).isTrue();
        }
    }

    @Test
    void stagingAlsoFailsWithoutSecrets() {
        Throwable failure = catchThrowable(() -> ProfileBoot.start("staging",
                "--MONGODB_URI=mongodb://127.0.0.1:1/kybr?replicaSet=rs0",
                "--KYBR_BFF_KEY_CURRENT=", "--KYBR_HASH_SECRET=", "--KYBR_IDEMPOTENCY_ENC_KEY=").close());

        assertThat(failure).isNotNull();
        assertThat(allMessages(failure)).contains("kybr.bff.current");
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
