package com.matheusmelo.kybr.support.qa;

import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.repository.RateLimitCounterRepository;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.service.ratelimit.RateLimitDecision;
import com.matheusmelo.kybr.service.ratelimit.RateLimitPolicy;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject;
import com.matheusmelo.kybr.service.ratelimit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acceptance probe: a client-IP test policy through the real {@link RateLimiter}
 * algorithm, real MongoDB counters and the real 429 rendering. Only the clock is the test's
 * ({@link QaClock}), so window positions are deterministic. Each test names its own policy.
 *
 * <p>The subject is {@link RateLimitSubject#forClient}; the policy takes that subject's kind, so a
 * shared address ({@code FORWARDED_ABSENT}) lands in the {@code ip-unknown} bucket.
 */
@RestController
public class QaRateLimitProbeController {

    private final RateLimiter limiter;

    public QaRateLimitProbeController(RateLimitCounterRepository repository, KeyHasher keyHasher,
            MeterRegistry meters) {
        this.limiter = new RateLimiter(repository, keyHasher, QaClock.INSTANCE, meters);
    }

    @GetMapping("/test/qa/rate/{policy}")
    Map<String, Object> consume(@PathVariable String policy, @RequestParam(defaultValue = "3") int limit,
            @RequestParam(defaultValue = "60") int window, HttpServletRequest request) {
        ClientIp clientIp = (ClientIp) request.getAttribute(ClientIp.ATTRIBUTE);
        RateLimitSubject subject = RateLimitSubject.forClient(clientIp, "qa");
        RateLimitPolicy rateLimitPolicy = new RateLimitPolicy(policy, limit, Duration.ofSeconds(window),
                ErrorCode.LIMITE_TENTATIVAS, subject.kind());
        RateLimitDecision decision = limiter.consumeOrThrow(rateLimitPolicy, subject);
        return Map.of("remaining", decision.remaining(), "subject_kind", subject.kind().name());
    }
}
