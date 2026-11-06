package com.matheusmelo.kybr.util;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Redacts secrets, capability tokens and PII from log text before it is written. Pure: no
 * Spring, no I/O; immutable configuration passed in, the keyed hash injected as a function.
 *
 * <p>Rules, applied in order:
 *
 * <ol>
 *   <li>The configured secret literals (BFF keys) → {@code [REDACTED]}.
 *   <li>{@code X-Kybr-BFF-Key[:=] v} → {@code X-Kybr-BFF-Key: [REDACTED]}.
 *   <li>{@code Bearer v} (any case) → {@code Bearer [REDACTED]}.
 *   <li>The path segment after a token prefix (default {@code cases}), anywhere in a path, with
 *       {@code /}, {@code //} or {@code %2F} separators → {@code tok:} + the first 12 characters of
 *       {@code hmac("casetoken:" + token)} (keyed, stable per token).
 *   <li>{@code Idempotency-Key}, {@code session}, {@code token}, {@code signature}, {@code sig}
 *       pairs ({@code =} or {@code :}, JSON or plain) → value {@code [REDACTED]}.
 *   <li>URL query values → {@code name=[REDACTED]}.
 *   <li>E-mail addresses → {@code [EMAIL]}.
 *   <li>CNPJs: the punctuated layout, or a valid canonical CNPJ → {@code [CNPJ]}.
 *   <li>CPF-shaped runs with a non-alphanumeric on both sides → {@link CpfMasker#MASK} (never a
 *       run inside a hex id or UUID).
 * </ol>
 *
 * <p>Idempotent over its own output ({@code tok:} segments and route-template placeholders such as
 * {@code {token}} are never re-hashed), null-safe, and every pattern is anchored by a look-behind
 * or bounded, so redaction is linear in the input.
 */
public final class LogRedactor {

    public static final String REDACTED = "[REDACTED]";

    private static final int TOKEN_HASH_CHARS = 12;
    private static final String SEPARATOR = "(?:/|%2[fF])";
    /**
     * A token segment: up to the next {@code / ? # ;}, whitespace, quote, bracket or {@code %2F}.
     * Written as an unrolled loop ({@code %} only when not starting {@code %2F}) to stay linear.
     */
    private static final String TOKEN_CHARS = "[^/?#;\\s\"'<>{}%]";
    private static final String TOKEN = "(?:" + TOKEN_CHARS + "|%(?!2[fF]))+";

    private static final Pattern BFF_HEADER = Pattern.compile("(?i)x-kybr-bff-key\\s*[:=]\\s*[^\\s,;\"']+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[^\\s,;\"']+");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)\\b(idempotency-key|session|token|signature|sig)(\"?\\s*[=:]\\s*\"?)(?!\\[REDACTED\\])[^\\s&,;\"'}\\]]+");
    private static final Pattern QUERY_VALUE = Pattern.compile("([?&])([A-Za-z0-9_.\\-]+)=([^&#\\s\"'<>]*)");
    private static final Pattern EMAIL = Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@"
            + "(?:[A-Za-z0-9-]{1,63}\\.){1,8}[A-Za-z]{2,24}(?![A-Za-z0-9-])");
    private static final Pattern CNPJ_PUNCTUATED = Pattern.compile(
            "(?<![0-9A-Za-z])[0-9A-Z]{2}\\.[0-9A-Z]{3}\\.[0-9A-Z]{3}/[0-9A-Z]{4}-[0-9]{2}(?![0-9A-Za-z])");
    private static final Pattern CNPJ_BARE = Pattern.compile("(?<![0-9A-Za-z])[0-9A-Z]{12}[0-9]{2}(?![0-9A-Za-z])");

    private final List<String> secretLiterals;
    private final Pattern caseToken;
    private final UnaryOperator<String> hmac;

    /**
     * @param tokenPrefixes path segments whose next segment is a capability token
     * @param secretLiterals exact values to mask anywhere (blank values are ignored)
     * @param hmac the keyed hash ({@code KeyHasher::hmac})
     */
    public LogRedactor(List<String> tokenPrefixes, Collection<String> secretLiterals, UnaryOperator<String> hmac) {
        this.secretLiterals = secretLiterals.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        this.hmac = Objects.requireNonNull(hmac, "hmac");
        this.caseToken = tokenPrefixes.isEmpty() ? null : Pattern.compile("(?<=/|%2[fF])((?i:"
                + tokenPrefixes.stream().map(Pattern::quote).collect(Collectors.joining("|"))
                + "))(" + SEPARATOR + "+)(?!(?i:tok:)|\\{)(" + TOKEN + ")");
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (String secret : secretLiterals) {
            out = out.replace(secret, REDACTED);
        }
        out = BFF_HEADER.matcher(out).replaceAll(Matcher.quoteReplacement("X-Kybr-BFF-Key: " + REDACTED));
        out = BEARER.matcher(out).replaceAll(Matcher.quoteReplacement("Bearer " + REDACTED));
        out = hashCaseTokens(out);
        out = KEY_VALUE.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + REDACTED));
        out = QUERY_VALUE.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + '=' + REDACTED));
        if (out.indexOf('@') >= 0) {
            out = EMAIL.matcher(out).replaceAll("[EMAIL]");
        }
        out = CNPJ_PUNCTUATED.matcher(out).replaceAll("[CNPJ]");
        out = CNPJ_BARE.matcher(out).replaceAll(m -> Cnpj.isValidCanonical(m.group()) ? "[CNPJ]" : m.group());
        return CpfMasker.maskBounded(out);
    }

    private String hashCaseTokens(String text) {
        if (caseToken == null) {
            return text;
        }
        Map<String, String> hashes = new HashMap<>();
        return caseToken.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + "tok:"
                + hashes.computeIfAbsent(m.group(3), t -> hmac.apply("casetoken:" + t).substring(0, TOKEN_HASH_CHARS))));
    }
}
