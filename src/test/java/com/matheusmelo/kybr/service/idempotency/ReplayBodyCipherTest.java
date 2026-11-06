package com.matheusmelo.kybr.service.idempotency;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Replay-body encryption. Every keyset here is generated in memory; none is committed. */
class ReplayBodyCipherTest {

    private static final byte[] BODY = "{\"case_token\":\"synthetic-marker-0001\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD = "record-id-a".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void registerTink() throws GeneralSecurityException {
        AeadConfig.register();
        MacConfig.register();
    }

    @Test
    void roundTripsWithTheSameAad() {
        ReplayBodyCipher cipher = new ReplayBodyCipher(encode(newKeyset(PredefinedAeadParameters.AES256_GCM)));

        byte[] ciphertext = cipher.encrypt(BODY, AAD);

        assertThat(ciphertext).isNotEqualTo(BODY);
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain("synthetic-marker-0001");
        assertThat(cipher.decrypt(ciphertext, AAD)).isEqualTo(BODY);
    }

    @Test
    void loadsTheTestProfileKeyset() {
        ReplayBodyCipher cipher = new ReplayBodyCipher(TestSecrets.IDEMPOTENCY_ENC_KEY);

        assertThat(cipher.decrypt(cipher.encrypt(BODY, AAD), AAD)).isEqualTo(BODY);
    }

    @Test
    void aDifferentAadFailsAsATypedException() {
        ReplayBodyCipher cipher = new ReplayBodyCipher(encode(newKeyset(PredefinedAeadParameters.AES256_GCM)));
        byte[] ciphertext = cipher.encrypt(BODY, AAD);

        assertThatThrownBy(() -> cipher.decrypt(ciphertext, "record-id-b".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ReplayBodyDecryptException.class);
    }

    @Test
    void aTamperedCiphertextFailsAsATypedException() {
        ReplayBodyCipher cipher = new ReplayBodyCipher(encode(newKeyset(PredefinedAeadParameters.AES256_GCM)));
        byte[] ciphertext = cipher.encrypt(BODY, AAD);
        ciphertext[ciphertext.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(ciphertext, AAD)).isInstanceOf(ReplayBodyDecryptException.class);
    }

    @Test
    void aKeysetWithoutTheKeyFailsAsATypedException() {
        byte[] ciphertext = new ReplayBodyCipher(encode(newKeyset(PredefinedAeadParameters.AES256_GCM)))
                .encrypt(BODY, AAD);
        ReplayBodyCipher otherKeyset = new ReplayBodyCipher(encode(newKeyset(PredefinedAeadParameters.AES256_GCM)));

        assertThatThrownBy(() -> otherKeyset.decrypt(ciphertext, AAD)).isInstanceOf(ReplayBodyDecryptException.class);
    }

    @Test
    void garbageCiphertextFailsAsATypedException() {
        ReplayBodyCipher cipher = new ReplayBodyCipher(encode(newKeyset(PredefinedAeadParameters.AES256_GCM)));

        assertThatThrownBy(() -> cipher.decrypt(new byte[3], AAD)).isInstanceOf(ReplayBodyDecryptException.class);
    }

    @Test
    void afterRotationOldCiphertextsStillDecryptAndNewOnesUseTheNewKey() throws GeneralSecurityException {
        KeysetHandle original = newKeyset(PredefinedAeadParameters.AES256_GCM);
        byte[] oldCiphertext = new ReplayBodyCipher(encode(original)).encrypt(BODY, AAD);

        KeysetHandle.Builder builder = KeysetHandle.newBuilder(original);
        KeysetHandle.Builder.Entry newPrimary =
                KeysetHandle.generateEntryFromParameters(PredefinedAeadParameters.AES256_GCM).withRandomId();
        builder.addEntry(newPrimary);
        newPrimary.makePrimary();
        KeysetHandle rotated = builder.build();
        ReplayBodyCipher rotatedCipher = new ReplayBodyCipher(encode(rotated));

        assertThat(rotatedCipher.decrypt(oldCiphertext, AAD)).isEqualTo(BODY);
        byte[] newCiphertext = rotatedCipher.encrypt(BODY, AAD);
        assertThat(keyIdPrefix(newCiphertext)).isEqualTo(rotated.getPrimary().getId())
                .isNotEqualTo(original.getPrimary().getId());
        assertThatThrownBy(() -> new ReplayBodyCipher(encode(original)).decrypt(newCiphertext, AAD))
                .isInstanceOf(ReplayBodyDecryptException.class);
    }

    @Test
    void startupFailsWithoutAnAes256GcmPrimary() {
        String aes128 = encode(newKeyset(PredefinedAeadParameters.AES128_GCM));

        assertThatIllegalStateException().isThrownBy(() -> new ReplayBodyCipher(aes128))
                .withMessageContaining("AES256_GCM");
    }

    @Test
    void startupFailsForANonAeadKeyset() {
        String mac = encode(newKeyset(PredefinedMacParameters.HMAC_SHA256_256BITTAG));

        assertThatIllegalStateException().isThrownBy(() -> new ReplayBodyCipher(mac));
    }

    @Test
    void startupFailsForAMalformedValueWithoutEchoingIt() {
        String notBase64 = "not base64 !!";
        String notAKeyset = Base64.getEncoder().encodeToString("{\"key\":[]}".getBytes(StandardCharsets.UTF_8));

        assertThatIllegalStateException().isThrownBy(() -> new ReplayBodyCipher(notBase64))
                .withMessageNotContaining(notBase64);
        assertThatIllegalStateException().isThrownBy(() -> new ReplayBodyCipher(notAKeyset))
                .withMessageNotContaining(notAKeyset);
        assertThatIllegalStateException().isThrownBy(() -> new ReplayBodyCipher(""));
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

    /** Tink's output prefix: 0x01 then the 4-byte big-endian key id. */
    private static int keyIdPrefix(byte[] ciphertext) {
        assertThat(ciphertext[0]).isEqualTo((byte) 0x01);
        return ((ciphertext[1] & 0xff) << 24) | ((ciphertext[2] & 0xff) << 16) | ((ciphertext[3] & 0xff) << 8)
                | (ciphertext[4] & 0xff);
    }
}
