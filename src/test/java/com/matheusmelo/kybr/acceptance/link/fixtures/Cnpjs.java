package com.matheusmelo.kybr.acceptance.link.fixtures;

import java.util.concurrent.ThreadLocalRandom;

/** Synthetic CNPJs with valid check digits (numeric and alphanumeric), for scenarios the stub doesn't know. */
public final class Cnpjs {

    private static final int[] FIRST = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] SECOND = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private Cnpjs() {
    }

    /** {@code base12} (digits or upper-case letters) completed with its two check digits. */
    public static String withCheckDigits(String base12) {
        if (base12.length() != 12) {
            throw new IllegalArgumentException("a CNPJ base has 12 characters");
        }
        String first = base12 + digit(base12, FIRST);
        return first + digit(first, SECOND);
    }

    /**
     * A fresh valid numeric CNPJ that no stub fixture uses (every fixture starts with another
     * digit), so the stub answers "not found" for it.
     */
    public static String unknownToTheStub() {
        StringBuilder base = new StringBuilder("60");
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 10; i++) {
            base.append(random.nextInt(10));
        }
        return withCheckDigits(base.toString());
    }

    private static int digit(String value, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (value.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }
}
