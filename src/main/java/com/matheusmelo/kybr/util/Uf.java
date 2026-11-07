package com.matheusmelo.kybr.util;

import java.util.Set;

/** Brazilian state codes (UF), pure and stateless: the 26 states and the Federal District. */
public final class Uf {

    private static final Set<String> CODES = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT",
            "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");

    private Uf() {
    }

    /** True only for an upper-case code of a state or the Federal District. */
    public static boolean isValid(String uf) {
        return uf != null && CODES.contains(uf);
    }
}
