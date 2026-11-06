package com.matheusmelo.kybr.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Caches a health indicator's result for a fixed time (so readiness pings MongoDB at most once
 * per 2 s however often it is probed). Done in code because Boot's health cache property doesn't
 * apply to group endpoints such as readiness. Concurrent callers share one check; a throwing
 * delegate counts as DOWN, without its exception text.
 */
public class CachedHealthIndicator implements HealthIndicator {

    private record Entry(Health health, Instant expiresAt) {
    }

    private final HealthIndicator delegate;
    private final Duration ttl;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong checks = new AtomicLong();
    private volatile Entry entry;

    public CachedHealthIndicator(HealthIndicator delegate, Duration ttl, Clock clock) {
        this.delegate = delegate;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Entry current = entry;
        if (isFresh(current)) {
            return current.health();
        }
        lock.lock();
        try {
            current = entry;
            if (isFresh(current)) {
                return current.health();
            }
            Health health = check();
            entry = new Entry(health, clock.instant().plus(ttl));
            return health;
        } finally {
            lock.unlock();
        }
    }

    /** How many times the delegate has actually been checked. */
    public long checks() {
        return checks.get();
    }

    private boolean isFresh(Entry current) {
        return current != null && clock.instant().isBefore(current.expiresAt());
    }

    private Health check() {
        checks.incrementAndGet();
        try {
            return delegate.health();
        } catch (RuntimeException e) {
            return Health.down().build();
        }
    }
}
