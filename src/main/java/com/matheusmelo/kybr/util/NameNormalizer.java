package com.matheusmelo.kybr.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalises a person's name for comparison, pure and stateless: NFKD (which also folds
 * full-width letters), combining marks removed, upper case ({@link Locale#ROOT}), the separators
 * {@code ' - .} dropped, and whitespace collapsed to single spaces and trimmed.
 */
public final class NameNormalizer {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern DROPPED = Pattern.compile("['\\-.]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private NameNormalizer() {
    }

    /** The normalised name; empty for null. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD);
        String plain = COMBINING_MARKS.matcher(decomposed).replaceAll("").toUpperCase(Locale.ROOT);
        return WHITESPACE.matcher(DROPPED.matcher(plain).replaceAll("")).replaceAll(" ").strip();
    }
}
