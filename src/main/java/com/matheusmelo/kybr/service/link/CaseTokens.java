package com.matheusmelo.kybr.service.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Case tokens: the seller's capability for one case. A token is 32 random bytes (256 bits) as
 * base64url without padding (43 characters). A case stores only {@link #hash}: SHA-256 of the
 * token's UTF-8 bytes as lower-case hex, unkeyed on purpose (the token's entropy makes a key add
 * nothing, and a key rotation would orphan every token).
 */
@Component
public class CaseTokens {

    /** The wire shape of a case token. */
    public static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9_-]{22,64}$");

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** A fresh token. */
    public String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The stored form of a token. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** True when the value has a token's wire shape. Null-safe. */
    public static boolean isWellFormed(String token) {
        return token != null && PATTERN.matcher(token).matches();
    }
}
