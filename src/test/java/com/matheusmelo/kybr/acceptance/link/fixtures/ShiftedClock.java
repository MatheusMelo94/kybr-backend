package com.matheusmelo.kybr.acceptance.link.fixtures;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application clock in the scripted acceptance context: real time plus a shift a test moves
 * (and resets afterwards), so leases, retries and the poller keep running while a test jumps a
 * cache lifetime or a day boundary. Views in another zone follow the same shift.
 */
public final class ShiftedClock extends Clock {

    private final AtomicReference<Duration> shift;
    private final ZoneId zone;

    public ShiftedClock() {
        this(new AtomicReference<>(Duration.ZERO), ZoneOffset.UTC);
    }

    private ShiftedClock(AtomicReference<Duration> shift, ZoneId zone) {
        this.shift = shift;
        this.zone = zone;
    }

    public void shiftBy(Duration duration) {
        shift.updateAndGet(current -> current.plus(duration));
    }

    /** Shifts so that "now" is {@code target}, then keeps running from there. */
    public void moveTo(Instant target) {
        shift.set(Duration.between(Instant.now(), target));
    }

    public void reset() {
        shift.set(Duration.ZERO);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return new ShiftedClock(shift, other);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(shift.get());
    }
}
