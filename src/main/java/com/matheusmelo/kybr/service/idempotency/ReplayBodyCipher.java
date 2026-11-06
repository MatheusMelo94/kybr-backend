package com.matheusmelo.kybr.service.idempotency;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Encrypts idempotency replay bodies with Google Tink AEAD: AES-256-GCM, AAD
 * = the record {@code _id}, so a body can't be moved to another record. No custom crypto.
 *
 * <p>The key is {@code KYBR_IDEMPOTENCY_ENC_KEY}: a base64 Tink JSON keyset, parsed once at
 * startup. Startup fails unless it is an AEAD keyset whose primary key is AES-256-GCM (32-byte
 * key, 12-byte IV, 16-byte tag). Rotation: add a new primary key and keep the old one for at
 * least 24 h (the longest TTL); old ciphertexts keep decrypting through Tink's key-id prefix.
 */
@Service
public class ReplayBodyCipher {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;

    private final Aead aead;

    @Autowired
    public ReplayBodyCipher(KybrProperties properties) {
        this(properties.idempotency().encKey());
    }

    public ReplayBodyCipher(String base64Keyset) {
        this.aead = load(base64Keyset);
    }

    public byte[] encrypt(byte[] plaintext, byte[] aad) {
        try {
            return aead.encrypt(plaintext, aad);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("replay body encryption failed", e);
        }
    }

    /** @throws ReplayBodyDecryptException on a wrong AAD, tampered bytes or an unknown key */
    public byte[] decrypt(byte[] ciphertext, byte[] aad) {
        try {
            return aead.decrypt(ciphertext, aad);
        } catch (GeneralSecurityException e) {
            throw new ReplayBodyDecryptException(e);
        }
    }

    private static Aead load(String base64Keyset) {
        KeysetHandle handle;
        try {
            AeadConfig.register();
            String json = new String(Base64.getDecoder().decode(base64Keyset), StandardCharsets.UTF_8);
            handle = TinkJsonProtoKeysetFormat.parseKeyset(json, InsecureSecretKeyAccess.get());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // never chain or echo: the cause may quote keyset bytes
            throw new IllegalStateException("kybr.idempotency.enc-key is not a base64 Tink JSON keyset");
        }
        if (!isAes256Gcm(handle.getPrimary().getKey())) {
            throw new IllegalStateException("kybr.idempotency.enc-key primary key must be AES256_GCM");
        }
        try {
            return handle.getPrimitive(RegistryConfiguration.get(), Aead.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("kybr.idempotency.enc-key is not an AEAD keyset");
        }
    }

    private static boolean isAes256Gcm(Key key) {
        return key.getParameters() instanceof AesGcmParameters parameters
                && parameters.getKeySizeBytes() == KEY_BYTES
                && parameters.getIvSizeBytes() == IV_BYTES
                && parameters.getTagSizeBytes() == TAG_BYTES;
    }
}
