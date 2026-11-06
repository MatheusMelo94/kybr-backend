package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

class CachedHealthIndicatorTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private final AtomicInteger checks = new AtomicInteger();
    private final AtomicReference<Status> delegateStatus = new AtomicReference<>(Status.UP);
    private final HealthIndicator delegate = () -> {
        checks.incrementAndGet();
        return Health.status(delegateStatus.get()).withDetail("secret", "detail").build();
    };
    private final CachedHealthIndicator cached = new CachedHealthIndicator(delegate, Duration.ofSeconds(2), clock);

    @Test
    void aHundredCallsWithinTwoSecondsCheckAtMostTwice() {
        for (int i = 0; i < 100; i++) {
            now.set(now.get().plusMillis(19));
            cached.health();
        }

        assertThat(checks.get()).isBetween(1, 2);
    }

    @Test
    void reChecksOnceTheCacheExpires() {
        assertThat(cached.health().getStatus()).isEqualTo(Status.UP);

        delegateStatus.set(Status.DOWN);
        now.set(now.get().plusMillis(1999));
        assertThat(cached.health().getStatus()).as("still cached").isEqualTo(Status.UP);

        now.set(now.get().plusMillis(1));
        assertThat(cached.health().getStatus()).isEqualTo(Status.DOWN);

        delegateStatus.set(Status.UP);
        now.set(now.get().plusSeconds(2));
        assertThat(cached.health().getStatus()).isEqualTo(Status.UP);
        assertThat(checks.get()).isEqualTo(3);
    }

    @Test
    void aThrowingDelegateIsDown() {
        CachedHealthIndicator failing = new CachedHealthIndicator(() -> {
            throw new IllegalStateException("connection refused");
        }, Duration.ofSeconds(2), clock);

        assertThat(failing.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
