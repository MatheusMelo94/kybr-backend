package com.matheusmelo.kybr.service;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.KybrProperties.Hashing;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * HMAC-SHA256 of a subject string with {@code KYBR_HASH_SECRET}, base64url without padding.
 * Used wherever a raw value must not be stored or logged: rate-limit subjects, idempotency keys,
 * case tokens in logs.
 *
 * <p>Thread-safe: each call uses its own {@link Mac} (cheap, and a ThreadLocal would be one per
 * virtual thread).
 */
@Service
public class KeyHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec current;
    private final SecretKeySpec previous;

    @Autowired
    public KeyHasher(KybrProperties properties) {
        this(properties.hashing());
    }

    public KeyHasher(Hashing hashing) {
        this.current = key(hashing.secret());
        this.previous = hashing.previous() == null ? null : key(hashing.previous());
    }

    /** The HMAC under the current secret. New records and counters always use this. */
    public String hmac(String subject) {
        return hmac(current, subject);
    }

    /**
     * The HMACs to look an existing record up by: {@code [current]}, or {@code [current, previous]}
     * while {@code KYBR_HASH_SECRET_PREVIOUS} is set after a rotation.
     */
    public List<String> hmacCandidates(String subject) {
        return previous == null ? List.of(hmac(subject)) : List.of(hmac(subject), hmac(previous, subject));
    }

    private static String hmac(SecretKeySpec key, String subject) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(subject.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        }
    }

    private static SecretKeySpec key(String base64Url) {
        return new SecretKeySpec(Base64.getUrlDecoder().decode(base64Url), ALGORITHM);
    }
}
