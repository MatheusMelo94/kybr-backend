package com.matheusmelo.kybr.service.idempotency;

import java.time.Duration;

/** Idempotency profiles and their record lifetimes. */
public enum IdempotencyProfile {
    /** Public routes under {@code /public/v/{slug}}: bound to the {@code slug} path variable. */
    PUBLIC_VERIFY(Duration.ofMinutes(10)),
    /** Panel writes: bound to the session's workspace (B3), or the constant {@code signup}. */
    PANEL(Duration.ofHours(24));

    private final Duration ttl;

    IdempotencyProfile(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration ttl() {
        return ttl;
    }
}
