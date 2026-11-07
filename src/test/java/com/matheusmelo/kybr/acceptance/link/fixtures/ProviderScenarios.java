package com.matheusmelo.kybr.acceptance.link.fixtures;

/**
 * The provider stub's scenario catalogue, as the acceptance suite drives it: one CNPJ per scenario,
 * the synthetic partner name each fixture carries, and the names to type for an exact, a partial and
 * no match. Every value is synthetic; the CNPJs carry valid check digits.
 */
public final class ProviderScenarios {

    /** The partner name on most fixtures; typing it is an exact match. */
    public static final String PARTNER = "Maria Exemplo Teste";
    /** Shares every token with {@link #PARTNER} but one: a partial match there. */
    public static final String PARTIAL_OF_PARTNER = "Maria Teste";
    /** Matches no fixture's partner. */
    public static final String STRANGER = "Pedro Ficticio Lima";
    /** The partner on the partial-match fixtures; never typed, so it must never be echoed. */
    public static final String PARTIAL_PARTNER = "Maria Aparecida Exemplo Teste";
    /** The partner on the unrelated-partner fixtures; never typed. */
    public static final String UNRELATED_PARTNER = "Roberto Ficticio Nunes";
    /** The micro-entrepreneur's name as it appears in the company name. */
    public static final String MEI_OWNER = "João Carlos Silva";
    /** The synthetic CPF one micro-entrepreneur fixture carries unmasked. */
    public static final String UNMASKED_CPF = "12345678909";
    /** Embedded in every stub fixture in a field the app never maps. */
    public static final String STUB_SENTINEL = "qa-stub-body-sentinel";

    public static final String APPROVED = "12345678000195";
    public static final String PARTIAL_MATCH = "98765432000198";
    public static final String BLOCKED_INAPTA = "11222333000181";
    public static final String APPROVED_ALPHANUMERIC = "12ABC34501DE35";
    public static final String MEI_MASKED = "36925814000184";
    public static final String MEI_UNMASKED = "47158263000197";
    public static final String NOT_FOUND = "71438562000160";
    public static final String LONG_NAME = "58293614000199";
    public static final String SLOW_WITHIN_TIMEOUT = "25873916000187";
    public static final String RECENT_30_DAYS = "52000001000130";
    public static final String RECENT_179_DAYS = "52000013000164";
    public static final String NOT_RECENT_180_DAYS = "52000014000109";
    public static final String OPENED_TODAY = "52000012000110";
    public static final String BLOCKED_SUSPENSA = "52000002000184";
    public static final String BLOCKED_BAIXADA = "52000003000129";
    public static final String BLOCKED_NULA = "52000004000173";
    public static final String UNRELATED_PARTNERS = "52000005000118";
    public static final String BLOCKED_RECENT_UNRELATED = "52000006000162";
    public static final String RECENT_AND_PARTIAL = "52000007000107";
    public static final String IE_INACTIVE = "52000010000120";
    public static final String INCOMPLETE_RECORD = "52000011000175";
    public static final String UNAVAILABLE = "52000015000153";
    public static final String TIMEOUT = "52000016000106";
    public static final String UNSUPPORTED = "5200A000000100";

    private ProviderScenarios() {
    }
}
