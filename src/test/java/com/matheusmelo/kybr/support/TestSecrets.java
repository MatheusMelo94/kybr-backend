package com.matheusmelo.kybr.support;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Test-only secrets, generated in memory when the test JVM starts. No key material is ever
 * committed: not in source, not in a yml file.
 */
public final class TestSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static final String BFF_KEY_CURRENT = randomKey();
    public static final String BFF_KEY_PREVIOUS = randomKey();
    public static final String HASH_SECRET = randomKey();
    public static final String HASH_SECRET_PREVIOUS = randomKey();
    /** A fresh Tink AES256_GCM JSON keyset, base64 (the KYBR_IDEMPOTENCY_ENC_KEY format). */
    public static final String IDEMPOTENCY_ENC_KEY = generateTinkKeyset();

    private TestSecrets() {
    }

    /** Base64url (no padding) of {@code length} bytes all equal to {@code fill}: for malformed or vector inputs. */
    public static String base64Url(int length, int fill) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @SuppressWarnings("deprecation") // the generate/serialize pair Tinkey's own tooling mirrors
    private static String generateTinkKeyset() {
        try {
            AeadConfig.register();
            KeysetHandle handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM);
            String json = TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get());
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot generate the test Tink keyset", e);
        }
    }
}
