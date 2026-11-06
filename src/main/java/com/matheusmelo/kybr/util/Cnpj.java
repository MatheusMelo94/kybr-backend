package com.matheusmelo.kybr.util;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * CNPJ rules: numeric and alphanumeric CNPJs, pure and stateless.
 *
 * <p><b>Wire policy ("reject"):</b> request CNPJs must already be canonical, checked with
 * {@link #isValidCanonical}. {@link #canonicalize} is for non-wire input only (provider
 * responses, internal tools) and is never applied silently to request input.
 */
public final class Cnpj {

    /** Twelve base characters ({@code 0-9A-Z}, ASCII only) and two numeric check digits. */
    private static final Pattern CANONICAL = Pattern.compile("[0-9A-Z]{12}[0-9]{2}");
    /** What {@link #canonicalize} strips: the usual punctuation and any whitespace. */
    private static final Pattern STRIPPED = Pattern.compile("[./\\-\\s]");

    private Cnpj() {
    }

    /**
     * True when {@code value} is exactly a canonical CNPJ: 14 ASCII characters, upper case, not
     * all the same, with correct check digits.
     */
    public static boolean isValidCanonical(String value) {
        if (value == null || !CANONICAL.matcher(value).matches() || allSame(value)) {
            return false;
        }
        return checkDigit(value, 12) == value.charAt(12) - '0' && checkDigit(value, 13) == value.charAt(13) - '0';
    }

    /**
     * Strips {@code . / -} and whitespace, upper-cases, then validates. Empty when the result
     * isn't a valid canonical CNPJ. For non-wire input only.
     */
    public static Optional<String> canonicalize(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String candidate = STRIPPED.matcher(value).replaceAll("").toUpperCase(Locale.ROOT);
        return isValidCanonical(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    /** The {@code 00.000.000/0000-00} layout (letters kept) of a valid canonical CNPJ. */
    public static String mask(String canonical) {
        if (!isValidCanonical(canonical)) {
            throw new IllegalArgumentException("not a valid canonical CNPJ");
        }
        return canonical.substring(0, 2) + '.' + canonical.substring(2, 5) + '.' + canonical.substring(5, 8) + '/'
                + canonical.substring(8, 12) + '-' + canonical.substring(12);
    }

    /**
     * The check digit at {@code position} (12 or 13) over the characters before it: value =
     * ASCII − 48, weights 2 to 9 cycling from the right, mod 11 (remainder &lt; 2 → 0, else 11 − r).
     */
    private static int checkDigit(String value, int position) {
        int sum = 0;
        int weight = 2;
        for (int i = position - 1; i >= 0; i--) {
            sum += (value.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    private static boolean allSame(String value) {
        return value.chars().allMatch(c -> c == value.charAt(0));
    }
}
