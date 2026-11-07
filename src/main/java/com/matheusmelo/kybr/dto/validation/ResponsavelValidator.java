package com.matheusmelo.kybr.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Backs {@link Responsavel}. Every pattern is bounded, so matching is linear in the input. */
public class ResponsavelValidator implements ConstraintValidator<Responsavel, String> {

    public static final int MAX_CODE_POINTS = 120;

    /** Letters with at most two combining marks each, space, apostrophe, hyphen and full stop. */
    private static final Pattern ALLOWED = Pattern.compile("^(?:\\p{L}\\p{M}{0,2}|[ '.-])+$");
    /** Hangul fillers: letters that render as blank space. */
    private static final Pattern HANGUL_FILLERS = Pattern.compile("[\\u3164\\uFFA0\\u115F\\u1160]");
    private static final Pattern LETTER = Pattern.compile("\\p{L}");

    /** The stored form: NFC, trimmed. Null-safe. */
    public static String normalize(String value) {
        return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || isValid(value);
    }

    /** True when the normalised value satisfies every rule; null is invalid here. */
    public static boolean isValid(String value) {
        String normalized = normalize(value);
        if (normalized == null || normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS
                || HANGUL_FILLERS.matcher(normalized).find() || !ALLOWED.matcher(normalized).matches()) {
            return false;
        }
        long words = Pattern.compile(" +").splitAsStream(normalized)
                .filter(word -> LETTER.matcher(word).find())
                .count();
        return words >= 2;
    }
}
