package com.matheusmelo.kybr.config.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties.Hashing;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.support.TestSecrets;
import com.matheusmelo.kybr.support.TestTokens;
import com.matheusmelo.kybr.util.LogRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

class LogRedactionTest {

    private static final String TOKEN = TestTokens.caseToken();

    @Test
    void buildsTheRedactorFromKybrProperties() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("kybr.hashing.secret", TestSecrets.HASH_SECRET)
                .withProperty("kybr.bff.current", TestSecrets.BFF_KEY_CURRENT)
                .withProperty("kybr.bff.previous", TestSecrets.BFF_KEY_PREVIOUS)
                .withProperty("kybr.logging.token-prefixes[0]", "cases")
                .withProperty("kybr.logging.token-prefixes[1]", "docs");

        LogRedactor redactor = LogRedaction.from(env);

        String expected = new KeyHasher(new Hashing(TestSecrets.HASH_SECRET, null)).hmac("casetoken:" + TOKEN).substring(0, 12);
        assertThat(redactor.redact("/x/docs/" + TOKEN)).isEqualTo("/x/docs/tok:" + expected);
        assertThat(redactor.redact("k=" + TestSecrets.BFF_KEY_CURRENT + " " + TestSecrets.BFF_KEY_PREVIOUS))
                .isEqualTo("k=[REDACTED] [REDACTED]");
    }

    /** The Tink keyset and the MongoDB password are masked too, raw and percent-decoded. */
    @Test
    void masksTheKeysetAndTheMongoPassword() {
        String password = "synthetic-p%40ss-" + TestTokens.caseToken();
        String decoded = password.replace("%40", "@");
        MockEnvironment env = new MockEnvironment()
                .withProperty("kybr.idempotency.enc-key", TestSecrets.IDEMPOTENCY_ENC_KEY)
                .withProperty("spring.mongodb.uri", "mongodb+srv://kybr-app:" + password + "@cluster0.example.net/kybr");

        LogRedactor redactor = LogRedaction.from(env);

        assertThat(redactor.redact("key " + TestSecrets.IDEMPOTENCY_ENC_KEY)).isEqualTo("key [REDACTED]");
        assertThat(redactor.redact("uri pw " + password + " / " + decoded)).isEqualTo("uri pw [REDACTED] / [REDACTED]");
        assertThat(LogRedaction.secretLiterals(env)).doesNotContain("kybr-app");
    }

    @Test
    void masksTheProviderKeyThePiiKeysetAndTheAdminTokens() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("kybr.cnpja.api-key", TestSecrets.CNPJA_API_KEY)
                .withProperty("kybr.pii.enc-key", TestSecrets.PII_ENC_KEY)
                .withProperty("kybr.admin.token", TestSecrets.ADMIN_TOKEN)
                .withProperty("kybr.admin.token-previous", TestSecrets.ADMIN_TOKEN_PREVIOUS);

        LogRedactor redactor = LogRedaction.from(env);

        assertThat(redactor.redact("a " + TestSecrets.CNPJA_API_KEY + " b " + TestSecrets.PII_ENC_KEY + " c "
                + TestSecrets.ADMIN_TOKEN + " d " + TestSecrets.ADMIN_TOKEN_PREVIOUS))
                .isEqualTo("a [REDACTED] b [REDACTED] c [REDACTED] d [REDACTED]");
    }

    @Test
    void aMongoUriWithoutCredentialsAddsNoLiteral() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.mongodb.uri", "mongodb://localhost:27017/kybr?replicaSet=rs0");

        assertThat(LogRedaction.secretLiterals(env)).isEmpty();
    }

    @Test
    void withoutAUsableHashSecretTokensAreStillHiddenUnderARandomKey() {
        LogRedactor missing = LogRedaction.from(new MockEnvironment());
        LogRedactor malformed = LogRedaction.from(new MockEnvironment().withProperty("kybr.hashing.secret", "short"));

        assertThat(missing.redact("/public/v/s/cases/" + TOKEN)).doesNotContain(TOKEN).contains("/cases/tok:");
        assertThat(malformed.redact("/public/v/s/cases/" + TOKEN)).doesNotContain(TOKEN).contains("/cases/tok:");
    }

    @Test
    void theListenerRunsAfterConfigDataAndBeforeLogging() {
        assertThat(new LogRedactionEnvironmentListener().getOrder())
                .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 10)
                .isLessThan(LoggingApplicationListener.DEFAULT_ORDER);
    }
}
