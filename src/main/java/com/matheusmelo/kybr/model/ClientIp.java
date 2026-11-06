package com.matheusmelo.kybr.model;

import java.util.Objects;

/**
 * The client IP a request is attributed to, and how it was learned.
 *
 * @param ip the address in canonical form (IPv4 dotted, IPv6 compressed lowercase)
 * @param source where it came from
 */
public record ClientIp(String ip, Source source) {

    /** The request attribute holding this request's {@code ClientIp}. */
    public static final String ATTRIBUTE = ClientIp.class.getName();

    public enum Source {
        /** {@code X-Kybr-Client-IP}, sent with a valid BFF key. */
        FORWARDED,
        /** A valid BFF key but no usable {@code X-Kybr-Client-IP}: the BFF's egress address. */
        FORWARDED_ABSENT,
        /** No BFF key: the platform (edge) socket IP. */
        DIRECT,
        /** No BFF key and no trustworthy platform header: a Fly-internal or bypassing address. */
        PLATFORM_ABSENT
    }

    public ClientIp {
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(source, "source");
    }

    /**
     * True only for {@code FORWARDED} and {@code DIRECT}. {@code FORWARDED_ABSENT} and
     * {@code PLATFORM_ABSENT} are shared addresses: never a per-IP rate-limit subject, and
     * audited as "IP unknown".
     */
    public boolean isPerClientIdentity() {
        return source == Source.FORWARDED || source == Source.DIRECT;
    }
}
