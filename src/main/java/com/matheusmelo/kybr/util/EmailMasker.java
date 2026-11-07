package com.matheusmelo.kybr.util;

/**
 * The only readable form of a seller's contact e-mail: the first character of the local part,
 * {@value #BULLETS}, {@code @} and the domain ({@code joao@empresa.com.br} →
 * {@code j•••@empresa.com.br}). A one-character local part shows no character at all
 * ({@code a@x.test} → {@code •••@x.test}), so the mask never reveals a whole local part. Pure and
 * stateless.
 */
public final class EmailMasker {

    public static final String BULLETS = "•••";

    private EmailMasker() {
    }

    /**
     * Masks an address, splitting on its last {@code @}. Without an {@code @}, returns
     * {@value #BULLETS} only. Null-safe.
     */
    public static String mask(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at < 0) {
            return BULLETS;
        }
        String local = email.substring(0, at);
        int firstLength = local.isEmpty() ? 0 : Character.charCount(local.codePointAt(0));
        String shown = local.length() > firstLength ? local.substring(0, firstLength) : "";
        return shown + BULLETS + email.substring(at);
    }
}
