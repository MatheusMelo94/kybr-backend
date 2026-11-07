package com.matheusmelo.kybr.service.ratelimit;

/** What a {@link RateLimitPolicy} counts per; a policy only accepts subjects of its kind. */
public enum SubjectKind {
    IP("ip"),
    IP_UNKNOWN("ip-unknown"),
    SLUG("slug"),
    CNPJ("cnpj"),
    ACCOUNT("account"),
    CASE("case");

    private final String prefix;

    SubjectKind(String prefix) {
        this.prefix = prefix;
    }

    /** The domain-separation prefix hashed with the value ({@code kind:value}). */
    public String prefix() {
        return prefix;
    }
}
