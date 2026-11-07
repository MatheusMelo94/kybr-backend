package com.matheusmelo.kybr.service.crypto;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.Key;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.AesGcmParameters;
import com.matheusmelo.kybr.config.KybrProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Encrypts personal-data fields (a seller's contact e-mail, an outbox payload) with Google Tink
 * AEAD: AES-256-GCM, AAD = the id of the document that holds the ciphertext, so a value can't be
 * moved to another document. No custom crypto.
 *
 * <p>The key is {@code KYBR_PII_ENC_KEY}: a base64 Tink JSON keyset, separate from the replay-body
 * keyset (one keyset per data class), parsed once at startup. Startup fails unless it is an AEAD
 * keyset whose primary key is AES-256-GCM (32-byte key, 12-byte IV, 16-byte tag). Rotation adds a
 * new primary and keeps old keys until every ciphertext under them is re-encrypted or deleted.
 */
@Service
public class FieldCipher {

    private static final String PROPERTY = "kybr.pii.enc-key";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;

    private final Aead aead;

    @Autowired
    public FieldCipher(KybrProperties properties) {
        this(properties.pii().encKey());
    }

    public FieldCipher(String base64Keyset) {
        this.aead = load(base64Keyset);
    }

    /** Encrypts {@code plaintext} for the document {@code documentId} (the AAD). */
    public byte[] encrypt(String plaintext, UUID documentId) {
        try {
            return aead.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), aad(documentId));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("personal-data field encryption failed");
        }
    }

    /** @throws FieldDecryptException on another document's id, tampered bytes or an unknown key */
    public String decrypt(byte[] ciphertext, UUID documentId) {
        try {
            return new String(aead.decrypt(ciphertext, aad(documentId)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new FieldDecryptException(e);
        }
    }

    private static byte[] aad(UUID documentId) {
        return documentId.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Aead load(String base64Keyset) {
        KeysetHandle handle;
        try {
            AeadConfig.register();
            String json = new String(Base64.getDecoder().decode(base64Keyset), StandardCharsets.UTF_8);
            handle = TinkJsonProtoKeysetFormat.parseKeyset(json, InsecureSecretKeyAccess.get());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // never chain or echo: the cause may quote keyset bytes
            throw new IllegalStateException(PROPERTY + " is not a base64 Tink JSON keyset");
        }
        if (!isAes256Gcm(handle.getPrimary().getKey())) {
            throw new IllegalStateException(PROPERTY + " primary key must be AES256_GCM");
        }
        try {
            return handle.getPrimitive(RegistryConfiguration.get(), Aead.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(PROPERTY + " is not an AEAD keyset");
        }
    }

    private static boolean isAes256Gcm(Key key) {
        return key.getParameters() instanceof AesGcmParameters parameters
                && parameters.getKeySizeBytes() == KEY_BYTES
                && parameters.getIvSizeBytes() == IV_BYTES
                && parameters.getTagSizeBytes() == TAG_BYTES;
    }
}
