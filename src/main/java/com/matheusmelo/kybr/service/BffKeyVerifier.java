package com.matheusmelo.kybr.service;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.KybrProperties.Bff;
import com.matheusmelo.kybr.model.BffKeySlot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.function.BiPredicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Dual-slot, constant-time check of a presented BFF key.
 *
 * <p>Both configured keys are SHA-256'd once at startup. A presented value is SHA-256'd and
 * compared with {@link MessageDigest#isEqual} against <b>both</b> slots on every call, with no
 * early exit; with no previous key, the second comparison runs against a random digest. Comparing
 * fixed-length digests also hides the configured keys' length. The value is compared byte-exact:
 * no trimming, case-sensitive.
 */
@Service
public class BffKeyVerifier {

    private final byte[] current;
    private final byte[] previous;
    private final boolean hasPrevious;
    private final BiPredicate<byte[], byte[]> comparator;

    @Autowired
    public BffKeyVerifier(KybrProperties properties) {
        this(properties.bff());
    }

    public BffKeyVerifier(Bff bff) {
        this(bff, MessageDigest::isEqual);
    }

    BffKeyVerifier(Bff bff, BiPredicate<byte[], byte[]> comparator) {
        this.current = sha256(bff.current());
        this.hasPrevious = bff.previous() != null;
        this.previous = hasPrevious ? sha256(bff.previous()) : randomDigest();
        this.comparator = comparator;
    }

    /** {@code CURRENT}, {@code PREVIOUS} or {@code NONE}; null, empty and blank values are {@code NONE}. */
    public BffKeySlot verify(String presented) {
        if (presented == null || presented.isBlank()) {
            return BffKeySlot.NONE;
        }
        byte[] digest = sha256(presented);
        boolean matchesCurrent = comparator.test(digest, current);
        boolean matchesPrevious = comparator.test(digest, previous);
        if (matchesCurrent) {
            return BffKeySlot.CURRENT;
        }
        return matchesPrevious && hasPrevious ? BffKeySlot.PREVIOUS : BffKeySlot.NONE;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] randomDigest() {
        byte[] digest = new byte[32];
        new SecureRandom().nextBytes(digest);
        return digest;
    }
}
