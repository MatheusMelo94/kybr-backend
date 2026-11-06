package com.matheusmelo.kybr.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks CPF-shaped runs in free text such as a <i>razão social</i>, pure and
 * stateless. Also the CPF rule of {@code LogRedactor} (no PII in logs), with stricter boundaries
 * ({@link #maskBounded}).
 *
 * <ol>
 *   <li>Every 14-character {@code [0-9A-Z]} token bounded by non-alphanumerics that is a valid
 *       canonical CNPJ (numeric or alphanumeric, e.g. {@code 12345678901A64}) is protected.
 *   <li>Every remaining run of 11 digits, with at most one separator ({@code . - /}, space, NBSP,
 *       tab) in each CPF position and no digit on either side, becomes {@value #MASK}. Partly
 *       masked forms ({@code ***.456.789-**}) are normalised to the full mask. A run glued to
 *       letters on its left is still masked.
 * </ol>
 *
 * <p>10- and 12-digit runs are untouched; an 11-digit phone-like run is masked (accepted
 * over-mask). Idempotent, null-safe, and no other text changes. Every pattern is bounded, so
 * matching is linear in the input.
 */
public final class CpfMasker {

    public static final String MASK = "***.***.***-**";

    private static final Pattern CNPJ_TOKEN = Pattern.compile("(?<![0-9A-Za-z])[0-9A-Z]{14}(?![0-9A-Za-z])");

    private static final String GROUP3 = "(?:[0-9]{3}|\\*{3})";
    private static final String GROUP2 = "(?:[0-9]{2}|\\*{2})";
    private static final String SEPARATOR = "[./\\- \\u00A0\\t]?";
    private static final String CPF_SHAPE = GROUP3 + SEPARATOR + GROUP3 + SEPARATOR + GROUP3 + SEPARATOR + GROUP2;
    private static final Pattern CPF_RUN = Pattern.compile("(?<![0-9])" + CPF_SHAPE + "(?![0-9])");
    private static final Pattern BOUNDED_CPF_RUN =
            Pattern.compile("(?<![0-9A-Za-z])" + CPF_SHAPE + "(?![0-9A-Za-z])");
    private static final Pattern ANY_DIGIT = Pattern.compile("[0-9]");

    private CpfMasker() {
    }

    public static String mask(String text) {
        return mask(text, CPF_RUN);
    }

    /**
     * As {@link #mask}, but a run must have a non-alphanumeric (or the text edge) on both sides.
     * The log rule: an 11-digit run inside a hex id, UUID or hash is not a CPF.
     */
    public static String maskBounded(String text) {
        return mask(text, BOUNDED_CPF_RUN);
    }

    private static String mask(String text, Pattern cpfRun) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<int[]> protectedSpans = validCnpjSpans(text);
        Matcher run = cpfRun.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (run.find()) {
            if (ANY_DIGIT.matcher(run.group()).find() && !overlaps(protectedSpans, run.start(), run.end())) {
                out.append(text, last, run.start()).append(MASK);
                last = run.end();
            }
        }
        return last == 0 ? text : out.append(text, last, text.length()).toString();
    }

    private static List<int[]> validCnpjSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher token = CNPJ_TOKEN.matcher(text);
        while (token.find()) {
            if (Cnpj.isValidCanonical(token.group())) {
                spans.add(new int[] {token.start(), token.end()});
            }
        }
        return spans;
    }

    private static boolean overlaps(List<int[]> spans, int start, int end) {
        for (int[] span : spans) {
            if (start < span[1] && span[0] < end) {
                return true;
            }
        }
        return false;
    }
}
