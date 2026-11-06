package com.matheusmelo.kybr.config.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties.Hashing;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.support.TestSecrets;
import com.matheusmelo.kybr.support.TestTokens;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import com.matheusmelo.kybr.util.LogRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Regression: in integration tests the console redactor sees the test secrets, as it sees the
 * real ones in production, so its literal-key backstop and its token hashes are the real ones.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConsoleRedactionIT extends WebIntegrationTest {

    @Autowired
    private LogRedactor contextRedactor;

    private static final Logger log = LoggerFactory.getLogger(ConsoleRedactionIT.class);
    private static final String TOKEN = TestTokens.caseToken();

    @Test
    void theConfiguredSecretsAreMaskedOnTheConsole(CapturedOutput output) {
        log.info("synthetic probe bff={} previous-less hash={}", TestSecrets.BFF_KEY_CURRENT, TestSecrets.HASH_SECRET);

        assertThat(output.getAll()).contains("synthetic probe bff=[REDACTED]")
                .doesNotContain(TestSecrets.BFF_KEY_CURRENT)
                .doesNotContain(TestSecrets.HASH_SECRET);
    }

    /** The replay-body keyset is a masked literal, on the console and in the access log's redactor. */
    @Test
    void theIdempotencyKeysetIsMaskedToo(CapturedOutput output) {
        log.info("synthetic probe keyset={}", TestSecrets.IDEMPOTENCY_ENC_KEY);

        assertThat(output.getAll()).contains("synthetic probe keyset=[REDACTED]")
                .doesNotContain(TestSecrets.IDEMPOTENCY_ENC_KEY);
        assertThat(contextRedactor.redact("k=" + TestSecrets.IDEMPOTENCY_ENC_KEY)).isEqualTo("k=[REDACTED]");
    }

    @Test
    void consoleTokenHashesUseTheConfiguredHashSecret(CapturedOutput output) {
        log.info("synthetic probe path=/public/v/s/cases/{}", TOKEN);

        String expected = "tok:" + new KeyHasher(new Hashing(TestSecrets.HASH_SECRET, null))
                .hmac("casetoken:" + TOKEN).substring(0, 12);
        assertThat(output.getAll()).contains("/cases/" + expected).doesNotContain(TOKEN);
    }
}
