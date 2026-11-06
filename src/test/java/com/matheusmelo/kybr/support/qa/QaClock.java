package com.matheusmelo.kybr.support.qa;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A movable UTC clock for the acceptance probes. Only those probes
 * use it, so moving it never affects the application's own {@code Clock} bean or other tests.
 */
public final class QaClock extends Clock {

    public static final QaClock INSTANCE = new QaClock();

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2030-01-01T00:00:00Z"));

    private QaClock() {
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    public void advance(Duration duration) {
        now.updateAndGet(i -> i.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("QaClock is UTC only");
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
