package com.matheusmelo.kybr.support;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Synthetic case tokens, generated at runtime so no token-shaped literal sits in the source (and
 * secret scanners stay clean without allowlists).
 */
public final class TestTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestTokens() {
    }

    /** A fresh 24-character base64url token (18 random bytes), the shape of a case token. */
    public static String caseToken() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
