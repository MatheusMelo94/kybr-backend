package com.matheusmelo.kybr.service.ratelimit;

import com.matheusmelo.kybr.model.ClientIp;
import java.util.Objects;
import java.util.UUID;

/**
 * Who a rate limit counts. Sealed, so every policy check is exhaustive.
 *
 * <p>{@link Ip} has no public constructor: it is built only by {@link #forClient} from a client IP
 * that is a per-client identity. Shared addresses become {@link IpUnknown}, and a per-IP policy
 * refuses those.
 */
public sealed interface RateLimitSubject {

    /**
     * The subject for a request's client IP: {@link Ip} only when
     * {@link ClientIp#isPerClientIdentity()}, otherwise {@link IpUnknown} for {@code scope} (e.g. the
     * slug), which a per-IP policy refuses. This is the only way to build an {@code Ip} from a
     * {@code ClientIp}.
     */
    static RateLimitSubject forClient(ClientIp clientIp, String scope) {
        Objects.requireNonNull(clientIp, "clientIp");
        IpUnknown unknown = new IpUnknown(scope);
        return clientIp.isPerClientIdentity() ? new Ip(clientIp.ip()) : unknown;
    }

    /**
     * Like {@link #forClient}, but a per-client IP is counted separately in each {@code scope} (e.g.
     * "per IP, per link"): an {@link Ip} of the address within the scope. A shared address is still
     * {@link IpUnknown} for the scope, never a per-IP subject.
     */
    static RateLimitSubject forClientInScope(ClientIp clientIp, String scope) {
        Objects.requireNonNull(clientIp, "clientIp");
        IpUnknown unknown = new IpUnknown(scope);
        return clientIp.isPerClientIdentity() ? new Ip(clientIp.ip() + SCOPE_SEPARATOR + scope) : unknown;
    }

    /** Joins a value and its scope; never part of an IP address, a slug or a CNPJ. */
    String SCOPE_SEPARATOR = "|";

    SubjectKind kind();

    /** The raw value; only ever hashed, never stored or logged. */
    String value();

    /** A per-client IP address. */
    final class Ip implements RateLimitSubject {
        private final String address;

        Ip(String address) {
            this.address = requireText(address, "address");
        }

        @Override
        public SubjectKind kind() {
            return SubjectKind.IP;
        }

        @Override
        public String value() {
            return address;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Ip ip && ip.address.equals(address);
        }

        @Override
        public int hashCode() {
            return address.hashCode();
        }

        @Override
        public String toString() {
            return "Ip[redacted]";
        }
    }

    /** The client IP is a shared address (FORWARDED_ABSENT, PLATFORM_ABSENT): counted per scope. */
    record IpUnknown(String scope) implements RateLimitSubject {
        public IpUnknown {
            requireText(scope, "scope");
        }

        @Override
        public SubjectKind kind() {
            return SubjectKind.IP_UNKNOWN;
        }

        @Override
        public String value() {
            return scope;
        }
    }

    record Slug(String slug) implements RateLimitSubject {
        public Slug {
            requireText(slug, "slug");
        }

        @Override
        public SubjectKind kind() {
            return SubjectKind.SLUG;
        }

        @Override
        public String value() {
            return slug;
        }
    }

    /** A canonical CNPJ (wire input is already canonical). */
    record Cnpj(String cnpj) implements RateLimitSubject {
        public Cnpj {
            requireText(cnpj, "cnpj");
        }

        @Override
        public SubjectKind kind() {
            return SubjectKind.CNPJ;
        }

        @Override
        public String value() {
            return cnpj;
        }

        @Override
        public String toString() {
            return "Cnpj[redacted]";
        }
    }

    /**
     * A CNPJ counted per link. The CNPJ is put in canonical form here, so a formatted and a bare
     * CNPJ share one counter; an invalid CNPJ is refused.
     */
    record SlugCnpj(String slug, String cnpj) implements RateLimitSubject {
        public SlugCnpj {
            requireText(slug, "slug");
            cnpj = com.matheusmelo.kybr.util.Cnpj.canonicalize(cnpj)
                    .orElseThrow(() -> new IllegalArgumentException("cnpj must be a valid CNPJ"));
        }

        @Override
        public SubjectKind kind() {
            return SubjectKind.CNPJ;
        }

        @Override
        public String value() {
            return slug + SCOPE_SEPARATOR + cnpj;
        }

        @Override
        public String toString() {
            return "SlugCnpj[slug=" + slug + ", cnpj=redacted]";
        }
    }

    /** A case, by id. */
    record CaseId(UUID caseId) implements RateLimitSubject {
        public CaseId {
            Objects.requireNonNull(caseId, "caseId");
        }

        @Override
        public SubjectKind kind() {
            return SubjectKind.CASE;
        }

        @Override
        public String value() {
            return caseId.toString();
        }
    }

    record Account(String accountId) implements RateLimitSubject {
        public Account {
            requireText(accountId, "accountId");
        }

        @Override
        public SubjectKind kind() {
            return SubjectKind.ACCOUNT;
        }

        @Override
        public String value() {
            return accountId;
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
