package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.matheusmelo.kybr.support.ProfileBoot;
import com.matheusmelo.kybr.support.TestSecrets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ConfigurableApplicationContext;

/** Staging and prod refuse to start without valid secrets. */
class KybrPropertiesStartupTest {

    private static final String NOT_BASE64URL = "not+base64/url+value+with+enough+characters+x";
    private static final String DECODES_TO_31_BYTES = TestSecrets.base64Url(31, 9);

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
                Arguments.of("idempotency key missing", "KYBR_IDEMPOTENCY_ENC_KEY", "", "kybr.idempotency.enc-key"));
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
