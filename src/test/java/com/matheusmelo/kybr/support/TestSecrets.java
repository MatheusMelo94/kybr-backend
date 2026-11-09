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
    /** A second, independent keyset (the KYBR_PII_ENC_KEY format): one keyset per data class. */
    public static final String PII_ENC_KEY = generateTinkKeyset();
    public static final String ADMIN_TOKEN = randomKey();
    public static final String ADMIN_TOKEN_PREVIOUS = randomKey();
    /** A synthetic provider API key; never a real one. */
    public static final String CNPJA_API_KEY = randomKey();
    /** A synthetic e-mail provider key in the provider's shape ({@code re_…}); never a real one. */
    public static final String RESEND_API_KEY = "re_" + randomAlphanumeric(8) + "_" + randomAlphanumeric(24);
    /**
     * PEM-armoured random bytes in the scanner's TLS variable formats: the right shape for startup
     * validation, never a usable certificate or key.
     */
    public static final String SCANNER_CLIENT_CERT = pem("CERTIFICATE");
    public static final String SCANNER_CLIENT_KEY = pem("PRIVATE KEY");
    public static final String SCANNER_CA = pem("CERTIFICATE");
    /** A SHA-256 pin of random bytes, base64 (the {@code kybr.scanner.spki-sha256} format). */
    public static final String SCANNER_SPKI_SHA256 = Base64.getEncoder().encodeToString(randomBytes(32));
    /** Synthetic static storage credentials in the cloud provider's shapes; never real ones. */
    public static final String AWS_ACCESS_KEY_ID = "AKIA" + randomUpperAlphanumeric(16);
    public static final String AWS_SECRET_ACCESS_KEY = randomAlphanumeric(40);

    private TestSecrets() {
    }

    /** Base64url (no padding) of {@code length} bytes all equal to {@code fill}: for malformed or vector inputs. */
    public static String base64Url(int length, int fill) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomKey() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String randomAlphanumeric(int length) {
        return randomFrom("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789", length);
    }

    private static String randomUpperAlphanumeric(int length) {
        return randomFrom("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567", length);
    }

    private static String randomFrom(String alphabet, int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private static String pem(String label) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(randomBytes(256));
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
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
