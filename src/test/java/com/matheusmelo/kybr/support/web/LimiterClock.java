package com.matheusmelo.kybr.support.web;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The rate limiter's clock in web integration tests: the system UTC clock until a test freezes it,
 * so a test can put every call in one fixed window. Only the limiter reads it (see
 * {@link LimiterClockConfiguration}); the application's own {@code Clock} is untouched. A test that
 * freezes it releases it in {@code @AfterEach}.
 */
public final class LimiterClock extends Clock {

    public static final LimiterClock INSTANCE = new LimiterClock();

    private final AtomicReference<Instant> frozen = new AtomicReference<>();

    private LimiterClock() {
    }

    public void freeze(Instant instant) {
        frozen.set(instant);
    }

    public void release() {
        frozen.set(null);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("LimiterClock is UTC only");
    }

    @Override
    public Instant instant() {
        Instant instant = frozen.get();
        return instant == null ? Instant.now() : instant;
    }
}
