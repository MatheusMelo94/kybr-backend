package com.matheusmelo.kybr.support;

import java.util.List;

/**
 * Dev-only: prints fresh values for the secrets the local and docker profiles require, as
 * {@code KEY=value} lines, using the same generators as the tests ({@link TestSecrets}).
 * {@code run-local.sh} appends the ones missing from the gitignored {@code .env} on first run.
 * Lives on the test classpath, so it never ships in the application jar.
 */
public final class DevEnvSecrets {

    private DevEnvSecrets() {
    }

    public static void main(String[] args) {
        lines().forEach(System.out::println);
    }

    static List<String> lines() {
        return List.of(
                "KYBR_BFF_KEY_CURRENT=" + TestSecrets.BFF_KEY_CURRENT,
                "KYBR_HASH_SECRET=" + TestSecrets.HASH_SECRET,
                "KYBR_IDEMPOTENCY_ENC_KEY=" + TestSecrets.IDEMPOTENCY_ENC_KEY);
    }
}
