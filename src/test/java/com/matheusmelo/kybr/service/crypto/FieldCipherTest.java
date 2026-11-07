package com.matheusmelo.kybr.service.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.Parameters;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import com.google.crypto.tink.mac.MacConfig;
import com.google.crypto.tink.mac.PredefinedMacParameters;
import com.matheusmelo.kybr.support.TestSecrets;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Personal-data field encryption. Every keyset here is generated in memory; none is committed. */
class FieldCipherTest {

    private static final String ADDRESS = "maria.exemplo@empresa-exemplo.test";

    @BeforeAll
    static void registerTink() throws GeneralSecurityException {
        AeadConfig.register();
        MacConfig.register();
    }

    @Test
    void roundTripsWithTheSameDocumentId() {
        FieldCipher cipher = new FieldCipher(TestSecrets.PII_ENC_KEY);
        UUID documentId = UUID.randomUUID();

        byte[] ciphertext = cipher.encrypt(ADDRESS, documentId);

        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain("maria.exemplo");
        assertThat(cipher.decrypt(ciphertext, documentId)).isEqualTo(ADDRESS);
    }

    @Test
    void anotherDocumentIdFailsAsATypedException() {
        FieldCipher cipher = new FieldCipher(TestSecrets.PII_ENC_KEY);
        byte[] ciphertext = cipher.encrypt(ADDRESS, UUID.randomUUID());

        assertThatThrownBy(() -> cipher.decrypt(ciphertext, UUID.randomUUID()))
                .isInstanceOf(FieldDecryptException.class)
                .hasMessageNotContaining(ADDRESS);
    }

    @Test
    void aTamperedCiphertextFailsAsATypedException() {
        FieldCipher cipher = new FieldCipher(TestSecrets.PII_ENC_KEY);
        UUID documentId = UUID.randomUUID();
        byte[] ciphertext = cipher.encrypt(ADDRESS, documentId);
        ciphertext[ciphertext.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(ciphertext, documentId)).isInstanceOf(FieldDecryptException.class);
    }

    @Test
    void anotherKeysetCannotDecrypt() {
        UUID documentId = UUID.randomUUID();
        byte[] ciphertext = new FieldCipher(TestSecrets.PII_ENC_KEY).encrypt(ADDRESS, documentId);

        assertThatThrownBy(() -> new FieldCipher(TestSecrets.IDEMPOTENCY_ENC_KEY).decrypt(ciphertext, documentId))
                .isInstanceOf(FieldDecryptException.class);
    }

    @Test
    void startupFailsWithoutAnAes256GcmPrimary() {
        String aes128 = encode(newKeyset(PredefinedAeadParameters.AES128_GCM));

        assertThatIllegalStateException().isThrownBy(() -> new FieldCipher(aes128))
                .withMessageContaining("kybr.pii.enc-key").withMessageContaining("AES256_GCM");
    }

    @Test
    void startupFailsForANonAeadKeyset() {
        String mac = encode(newKeyset(PredefinedMacParameters.HMAC_SHA256_256BITTAG));

        assertThatIllegalStateException().isThrownBy(() -> new FieldCipher(mac));
    }

    @Test
    void startupFailsForAMalformedValueWithoutEchoingIt() {
        String notBase64 = "not base64 !!";

        assertThatIllegalStateException().isThrownBy(() -> new FieldCipher(notBase64))
                .withMessageNotContaining(notBase64).withMessageContaining("kybr.pii.enc-key");
    }

    private static KeysetHandle newKeyset(Parameters parameters) {
        try {
            return KeysetHandle.generateNew(parameters);
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    private static String encode(KeysetHandle handle) {
        try {
            String json = TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get());
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }
}
