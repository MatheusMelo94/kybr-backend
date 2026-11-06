package com.matheusmelo.kybr.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.specification.RequestSpecification;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * "No secret reaches the logs": per-run synthetic canaries, the requests that carry them through
 * every log path B0 has, and the check that the captured output contains none of them in raw,
 * URL-encoded or base64 form.
 */
final class SecretCanaries {

    final String bearer = "qaBearer" + compact();
    final String caseToken = "qaTok_" + compact();
    final String signature = "qaSig" + compact();
    final String idempotencyKey = UUID.randomUUID().toString();
    final String bodyCanary = "qaBody" + compact();
    final String invalidKey = "qaInvalidKey" + compact() + compact();
    final String throwCanary = "qaThrow" + compact();
    static final String CNPJ = "12345678000195";

    private static String compact() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Sends every canary-bearing request; {@code keyed} carries the BFF key, {@code unkeyed} doesn't. */
    void sendAll(Supplier<RequestSpecification> keyed, Supplier<RequestSpecification> unkeyed) {
        // Configured key on many routes, an invalid candidate, a bearer and both on a rejected route.
        keyed.get().header("Authorization", "Bearer " + bearer).get("/test/web/ok");
        unkeyed.get().header("X-Kybr-BFF-Key", invalidKey).header("Authorization", "Bearer " + bearer).get("/v1/x");
        unkeyed.get().header("X-Kybr-BFF-Key", invalidKey).get("/livez");
        // A case token in a matched case-page path, an unmatched one and raw-URI variants; CNPJ and signature values.
        keyed.get().get("/public/v/s/cases/" + caseToken + "?cnpj=" + CNPJ + "&signature=" + signature + "&expires=1");
        keyed.get().get("/public/v/s/cases/" + caseToken + "/nope?cnpj=" + CNPJ);
        keyed.get().urlEncodingEnabled(false).get("/public/v/s/cases//" + caseToken);
        keyed.get().urlEncodingEnabled(false).get("/public/v/s/cases/" + caseToken + ";x=y");
        unkeyed.get().get("/public/v/s/cases/" + caseToken + "/contact?signature=" + signature);
        // The idempotency key and a response body canary on an idempotent route (plus a replay).
        for (int i = 0; i < 2; i++) {
            keyed.get().contentType("application/json").header("Idempotency-Key", idempotencyKey)
                    .body("{\"name\":\"" + bodyCanary + "\"}").post("/public/v/qa-sweep-" + idempotencyKey + "/verify-test");
        }
        keyed.get().contentType("application/json").header("Idempotency-Key", idempotencyKey)
                .body("{\"name\":\"other\"}").post("/public/v/qa-sweep-" + idempotencyKey + "/verify-test");
        // Jackson errors with the canary in the source snippet; a CNPJ in a body.
        keyed.get().contentType("application/json").body("{\"display_name\":\"" + bodyCanary + "\",")
                .post("/test/web/validation/body");
        keyed.get().contentType("application/json").body("{\"display_name\":\"" + bodyCanary + "\",\"x\":\"" + bodyCanary + "\"}")
                .post("/test/web/validation/body");
        keyed.get().contentType("application/json").body("{\"display_name\":\"a\",\"cnpj\":\"" + CNPJ + "x\"}")
                .post("/test/web/validation/body");
        keyed.get().get("/test/web/validation/query?cnpj=" + CNPJ + "&name=" + bodyCanary);
        // An exception whose message carries a bearer, a case-token path and the configured key.
        keyed.get().get("/test/qa/throw/" + throwCanary);
    }

    /** Asserts that none of the canaries, nor the configured key, appear in {@code output} in any form. */
    void assertAbsentFrom(String output, String configuredKey) {
        List<String> secrets = new ArrayList<>(List.of(configuredKey, bearer, caseToken, signature, idempotencyKey,
                bodyCanary, invalidKey, CNPJ, throwCanary + "-bearer", throwCanary + "-token"));
        List<String> forms = new ArrayList<>();
        for (String secret : secrets) {
            forms.add(secret);
            forms.add(URLEncoder.encode(secret, StandardCharsets.UTF_8));
            forms.add(Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
            forms.add(Base64.getUrlEncoder().withoutPadding().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
        }
        assertThat(output).as("captured log output").doesNotContain(forms.toArray(String[]::new));
    }
}
