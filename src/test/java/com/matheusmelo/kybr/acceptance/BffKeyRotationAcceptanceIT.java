package com.matheusmelo.kybr.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.KybrApplication;
import com.matheusmelo.kybr.config.logging.LogRedaction;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.TestSecrets;
import io.restassured.RestAssured;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Dual-key BFF key rotation, three phases on
 * one MongoDB:
 *
 * <ol>
 *   <li>{@code CURRENT=K1}, no {@code PREVIOUS} (the shared test context): K1 passes, K2 is 403;
 *   <li>{@code CURRENT=K2, PREVIOUS=K1}: both pass, and {@code PREVIOUS} traffic is counted per slot;
 *   <li>{@code CURRENT=K2}, {@code PREVIOUS} unset: K1 is 403, K2 passes.
 * </ol>
 *
 * Phases 2 and 3 are short-lived app instances started and closed inside the test (one at a time,
 * to keep memory flat); they are never cached.
 */
class BffKeyRotationAcceptanceIT extends AcceptanceTest {

    private static final String K1 = TestSecrets.BFF_KEY_CURRENT;
    private static final String K2 = TestSecrets.BFF_KEY_PREVIOUS;

    @Autowired
    private Environment environment;

    /** Each boot rebuilds the JVM-wide console redactor; restore the shared context's. */
    @AfterEach
    void restoreTheSharedRedactor() {
        LogRedaction.configure(environment);
    }

    @Test
    void aRotationAcceptsBothKeysOnlyWhileBothAreConfigured() {
        // Phase 1: the shared context runs with CURRENT=K1 and no PREVIOUS.
        assertThat(status(port, K1)).isEqualTo(200);
        assertThat(status(port, K2)).isEqualTo(403);

        // Phase 2: CURRENT=K2, PREVIOUS=K1.
        try (ConfigurableApplicationContext app = boot(K2, K1)) {
            int appPort = app.getEnvironment().getProperty("local.server.port", Integer.class);
            int managementPort = app.getEnvironment().getProperty("local.management.port", Integer.class);
            assertThat(status(appPort, K2)).isEqualTo(200);
            assertThat(status(appPort, K1)).isEqualTo(200);
            assertThat(Metrics.sample(managementPort, "kybr_bff_key_accepted_total", "slot=\"PREVIOUS\""))
                    .as("PREVIOUS traffic is visible, so the rotation can be finished").isGreaterThanOrEqualTo(1.0);
        }

        // Phase 3: PREVIOUS removed.
        try (ConfigurableApplicationContext app = boot(K2, "")) {
            int appPort = app.getEnvironment().getProperty("local.server.port", Integer.class);
            assertThat(status(appPort, K1)).isEqualTo(403);
            assertThat(status(appPort, K2)).isEqualTo(200);
        }
    }

    private static int status(int targetPort, String key) {
        return RestAssured.given().port(targetPort).header(BFF_KEY_HEADER, key).get("/test/web/ok").statusCode();
    }

    private static ConfigurableApplicationContext boot(String current, String previous) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.mongodb.uri=" + MongoIntegrationTest.mongoUri(),
                "--spring.mongodb.database=" + MongoIntegrationTest.database(),
                "--kybr.bff.current=" + current,
                "--kybr.hashing.secret=" + TestSecrets.HASH_SECRET,
                "--kybr.idempotency.enc-key=" + TestSecrets.IDEMPOTENCY_ENC_KEY));
        args.add("--kybr.bff.previous=" + previous);
        return new SpringApplicationBuilder(KybrApplication.class).profiles("test").run(args.toArray(String[]::new));
    }
}
