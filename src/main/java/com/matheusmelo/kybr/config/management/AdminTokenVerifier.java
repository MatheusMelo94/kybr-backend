package com.matheusmelo.kybr.config.management;

import com.matheusmelo.kybr.model.link.TokenSlot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * Dual-slot, constant-time check of a presented admin token, the same scheme as the BFF key:
 * both configured tokens are SHA-256'd once, a presented value is SHA-256'd and compared with
 * {@link MessageDigest#isEqual} against <b>both</b> slots on every call, with no early exit (a
 * random digest stands in for a missing previous token). Byte-exact: no trimming, case-sensitive.
 */
public class AdminTokenVerifier {

    private final byte[] current;
    private final byte[] previous;
    private final boolean hasPrevious;
    private final BiPredicate<byte[], byte[]> comparator;

    public AdminTokenVerifier(String current, String previous) {
        this(current, previous, MessageDigest::isEqual);
    }

    AdminTokenVerifier(String current, String previous, BiPredicate<byte[], byte[]> comparator) {
        this.current = sha256(current);
        this.hasPrevious = previous != null && !previous.isEmpty();
        this.previous = hasPrevious ? sha256(previous) : randomDigest();
        this.comparator = comparator;
    }

    /** The slot the token matches; empty for anything else, including null and empty values. */
    public Optional<TokenSlot> verify(String presented) {
        if (presented == null || presented.isEmpty()) {
            return Optional.empty();
        }
        byte[] digest = sha256(presented);
        boolean matchesCurrent = comparator.test(digest, current);
        boolean matchesPrevious = comparator.test(digest, previous);
        if (matchesCurrent) {
            return Optional.of(TokenSlot.CURRENT);
        }
        return matchesPrevious && hasPrevious ? Optional.of(TokenSlot.PREVIOUS) : Optional.empty();
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
