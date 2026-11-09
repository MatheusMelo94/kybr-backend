package com.matheusmelo.kybr.util;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes seller-typed text safe to put in an e-mail. Pure and stateless.
 *
 * <ol>
 *   <li>Control and format characters are removed (a zero-width joiner, a bidi override or
 *       isolate); line breaks, tabs and line or paragraph separators become a space.
 *   <li>The text is NFC-normalised, runs of whitespace collapse to one space, and the ends are
 *       trimmed.
 *   <li>Anything a mail client could turn into a link is neutralised: {@code ://} becomes
 *       {@code [:]//}, and every dot inside a host-like run ({@code evil.example}, also with a
 *       full-width or ideographic dot) becomes {@code [.]}. A run counts as host-like when its last
 *       label is two or more letters or a Punycode label ({@code xn--…}), so initials ({@code J.R.}) and numbers ({@code 1.500}) stay.
 *   <li>The result is cut to a maximum number of characters (code points, never half a pair).
 * </ol>
 *
 * Idempotent and null-safe. Every pattern is linear in the input.
 */
public final class SellerText {

    /** The longest {@code responsavel} kept. */
    public static final int RESPONSAVEL_MAX = 120;
    /** The longest {@code nota_vendedor} kept. */
    public static final int NOTA_VENDEDOR_MAX = 280;
    /** The longest e-mail address kept. */
    public static final int EMAIL_MAX = 254;
    /** The longest razão social kept. */
    public static final int RAZAO_SOCIAL_MAX = 300;

    private static final String DOTS = ".．。｡";
    private static final Pattern HOST_RUN =
            Pattern.compile("[\\p{L}\\p{N}-]++(?:[" + DOTS + "][\\p{L}\\p{N}-]++)++");
    private static final Pattern DOT = Pattern.compile("[" + DOTS + "]");
    private static final Pattern LAST_LABEL_IS_A_NAME =
            Pattern.compile(".*[" + DOTS + "](?:\\p{L}{2,}+|(?i:xn--)[\\p{L}\\p{N}-]++)");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Zs}]++");

    private SellerText() {
    }

    /** The name of the person a seller named, cleaned and cut to {@value #RESPONSAVEL_MAX} characters. */
    public static String responsavel(String text) {
        return clean(text, RESPONSAVEL_MAX);
    }

    /** A seller's note, cleaned and cut to {@value #NOTA_VENDEDOR_MAX} characters. */
    public static String notaVendedor(String text) {
        return clean(text, NOTA_VENDEDOR_MAX);
    }

    /**
     * A company's razão social (provider data, which can hold a sole trader's CPF): cleaned, with
     * every CPF-shaped run masked before the cut, so a cut never leaves part of one readable.
     */
    public static String razaoSocial(String text) {
        return clean(CpfMasker.mask(clean(text, Integer.MAX_VALUE)), RAZAO_SOCIAL_MAX);
    }

    /** Cleans {@code text} and cuts it to {@code maxCodePoints} characters. */
    public static String clean(String text, int maxCodePoints) {
        if (text == null) {
            return null;
        }
        String visible = Normalizer.normalize(removeInvisible(text), Normalizer.Form.NFC);
        String neutral = neutraliseHosts(collapse(visible).replace("://", "[:]//"));
        return collapse(cut(neutral, maxCodePoints));
    }

    private static String removeInvisible(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (Character.isWhitespace(codePoint) || codePoint == 0x85 || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                out.append(' ');
            } else if (type != Character.CONTROL && type != Character.FORMAT && type != Character.SURROGATE) {
                out.appendCodePoint(codePoint);
            }
        });
        return out.toString();
    }

    private static String collapse(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }

    private static String neutraliseHosts(String text) {
        Matcher run = HOST_RUN.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        while (run.find()) {
            String host = run.group();
            String replacement = LAST_LABEL_IS_A_NAME.matcher(host).matches() ? DOT.matcher(host).replaceAll("[.]") : host;
            run.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        run.appendTail(out);
        return out.toString();
    }

    private static String cut(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }
}
