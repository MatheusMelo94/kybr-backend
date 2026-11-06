package com.matheusmelo.kybr.config.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.KybrProperties.Hashing;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.support.ProfileBoot;
import com.matheusmelo.kybr.support.TestSecrets;
import com.matheusmelo.kybr.support.TestTokens;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The real logging pipeline per profile redacts message and exception text. */
@ExtendWith(OutputCaptureExtension.class)
class RedactedLoggingTest {

    private static final String TOKEN = TestTokens.caseToken();
    private static final String BEARER = "eyJzeW50aGV0aWMiOiJiZWFyZXIifQ.c2ln";
    private static final Logger log = LoggerFactory.getLogger(RedactedLoggingTest.class);

    private ConfigurableApplicationContext context;

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void prodJsonRedactsTheMessageTheErrorMessageAndTheStackTrace(CapturedOutput output) {
        context = ProfileBoot.start("prod", ProfileBoot.validCloudEnv().toArray(String[]::new));

        logSecrets();

        JsonNode event = lineContaining(output, "\"error\"");
        String expectedTok = tok(TestSecrets.HASH_SECRET);
        assertThat(event.path("message").asString()).contains("Bearer [REDACTED]", expectedTok, "[REDACTED]");
        assertThat(event.path("error").path("message").asString()).contains("Bearer [REDACTED]", expectedTok);
        assertThat(event.path("error").path("stack_trace").asString()).contains("Bearer [REDACTED]", expectedTok);
        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain(BEARER)
                .doesNotContain(TestSecrets.BFF_KEY_CURRENT);
    }

    @Test
    void localPatternIsRedactedTheSameWay(CapturedOutput output) {
        context = ProfileBoot.start("local", ProfileBoot.validCloudEnv().toArray(String[]::new));
        String localSecret = context.getBean(KybrProperties.class).hashing().secret();
        assertThat(localSecret).isEqualTo(TestSecrets.HASH_SECRET);

        logSecrets();

        String all = output.getAll();
        assertThat(all).contains("synthetic failure", "Bearer [REDACTED]", tok(localSecret))
                .contains("at com.matheusmelo.kybr.config.logging.RedactedLoggingTest")
                .doesNotContain(TOKEN).doesNotContain(BEARER);
        assertThat(all.lines().filter(line -> line.startsWith("{"))).as("pattern, not JSON").isEmpty();
    }

    private void logSecrets() {
        String path = "/public/v/s/cases/" + TOKEN + "/contact";
        log.error("synthetic failure Authorization: Bearer {} on {} key {}", BEARER, path, TestSecrets.BFF_KEY_CURRENT,
                new IllegalStateException("upstream rejected Bearer " + BEARER + " for " + path));
    }

    private static String tok(String secret) {
        return "tok:" + new KeyHasher(new Hashing(secret, null)).hmac("casetoken:" + TOKEN).substring(0, 12);
    }

    private static JsonNode lineContaining(CapturedOutput output, String marker) {
        JsonMapper mapper = JsonMapper.builder().build();
        List<String> lines = output.getAll().lines()
                .filter(line -> line.startsWith("{") && line.contains("synthetic failure") && line.contains(marker))
                .toList();
        assertThat(lines).as("one ECS JSON line for the event").hasSize(1);
        return mapper.readTree(lines.getFirst());
    }
}
