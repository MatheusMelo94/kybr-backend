package com.matheusmelo.kybr.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.matheusmelo.kybr.service.idempotency.ReplayBodyCipher;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** run-local.sh's first-run generator: one fresh, valid {@code KEY=value} line per required dev secret. */
class DevEnvSecretsTest {

    private static Map<String, String> parse(List<String> lines) {
        return lines.stream().map(line -> line.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));
    }

    @Test
    void printsTheThreeRequiredSecretsAsEnvLines() {
        List<String> lines = DevEnvSecrets.lines();

        assertThat(lines).allMatch(line -> line.matches("[A-Z_]+=\\S+"));
        assertThat(parse(lines)).containsOnlyKeys(
                "KYBR_BFF_KEY_CURRENT", "KYBR_HASH_SECRET", "KYBR_IDEMPOTENCY_ENC_KEY");
    }

    @Test
    void theKeysAreBase64UrlOfAtLeast32BytesAndTheKeysetLoads() {
        Map<String, String> env = parse(DevEnvSecrets.lines());

        assertThat(Base64.getUrlDecoder().decode(env.get("KYBR_BFF_KEY_CURRENT"))).hasSizeGreaterThanOrEqualTo(32);
        assertThat(Base64.getUrlDecoder().decode(env.get("KYBR_HASH_SECRET"))).hasSizeGreaterThanOrEqualTo(32);
        assertThat(env.get("KYBR_BFF_KEY_CURRENT")).isNotEqualTo(env.get("KYBR_HASH_SECRET"));
        assertThatCode(() -> new ReplayBodyCipher(env.get("KYBR_IDEMPOTENCY_ENC_KEY"))).doesNotThrowAnyException();
    }
}
