package com.matheusmelo.kybr.service.ratelimit;

import com.matheusmelo.kybr.exception.RateLimitExceededException;
import com.matheusmelo.kybr.exception.ServiceUnavailableException;
import com.matheusmelo.kybr.model.RateLimitCounter;
import com.matheusmelo.kybr.repository.RateLimitCounterRepository;
import com.matheusmelo.kybr.service.KeyHasher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Fixed-window rate limiting on MongoDB counters (no extra infrastructure). Called explicitly by
 * services. Fails closed: a MongoDB failure is a {@link ServiceUnavailableException} (503).
 *
 * <p>Window: {@code start = floor(now / window)}, end = start + window. The counter is keyed by
 * {@code KeyHasher.hmac(kind + ":" + value)}, never the raw subject, and expires one minute after
 * the window end. {@code Retry-After = max(1, ceil(end − now))}.
 */
@Service
public class RateLimiter {

    private static final long EXPIRY_GRACE_SECONDS = 60;

    private final RateLimitCounterRepository repository;
    private final KeyHasher keyHasher;
    private final Clock clock;
    private final MeterRegistry meters;

    public RateLimiter(RateLimitCounterRepository repository, KeyHasher keyHasher, Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.keyHasher = keyHasher;
        this.clock = clock;
        this.meters = meters;
    }

    /** Counts this call and decides whether it is within the limit. */
    public RateLimitDecision consume(RateLimitPolicy policy, RateLimitSubject subject) {
        Window window = window(policy, subject);
        if (subject instanceof RateLimitSubject.IpUnknown) {
            meters.counter("kybr.ratelimit.ip_unknown", "policy", policy.name()).increment();
        }
        long count;
        try {
            count = repository.incrementAndGet(window.counterId(), Instant.ofEpochSecond(window.end() + EXPIRY_GRACE_SECONDS));
        } catch (DataAccessException e) {
            throw new ServiceUnavailableException(e);
        }
        RateLimitDecision decision = decide(policy, window, count <= policy.limit(), count);
        if (!decision.allowed()) {
            meters.counter("kybr.ratelimit.exceeded", "policy", policy.name()).increment();
        }
        return decision;
    }

    /** Whether the next call would be allowed, without counting (e.g. "challenge after 3 failures"). */
    public RateLimitDecision peek(RateLimitPolicy policy, RateLimitSubject subject) {
        Window window = window(policy, subject);
        long count;
        try {
            count = repository.findById(window.counterId()).map(RateLimitCounter::count).orElse(0L);
        } catch (DataAccessException e) {
            throw new ServiceUnavailableException(e);
        }
        return decide(policy, window, count < policy.limit(), count);
    }

    /** {@link #consume}, throwing {@link RateLimitExceededException} (429 + Retry-After) on denial. */
    public RateLimitDecision consumeOrThrow(RateLimitPolicy policy, RateLimitSubject subject) {
        RateLimitDecision decision = consume(policy, subject);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(policy.errorCode(), decision.retryAfterSeconds());
        }
        return decision;
    }

    private Window window(RateLimitPolicy policy, RateLimitSubject subject) {
        if (subject.kind() != policy.subjectKind()) {
            throw new IllegalArgumentException(
                    "policy " + policy.name() + " counts " + policy.subjectKind() + ", not " + subject.kind());
        }
        Instant now = clock.instant();
        long length = policy.window().toSeconds();
        long start = Math.floorDiv(now.getEpochSecond(), length) * length;
        String subjectHmac = keyHasher.hmac(subject.kind().prefix() + ":" + subject.value());
        return new Window(RateLimitCounter.idFor(policy.name(), subjectHmac, start), start + length, now);
    }

    private static RateLimitDecision decide(RateLimitPolicy policy, Window window, boolean allowed, long count) {
        long remaining = Math.max(0, policy.limit() - count);
        return new RateLimitDecision(allowed, remaining, allowed ? 0 : window.secondsToEnd());
    }

    private record Window(String counterId, long end, Instant now) {
        /** {@code max(1, ceil(end − now))} in whole seconds. */
        long secondsToEnd() {
            long millis = end * 1000 - now.toEpochMilli();
            return Math.max(1, Math.ceilDiv(millis, 1000));
        }
    }
}
