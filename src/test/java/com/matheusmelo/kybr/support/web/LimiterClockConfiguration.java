package com.matheusmelo.kybr.support.web;

import com.matheusmelo.kybr.repository.RateLimitCounterRepository;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.service.ratelimit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Makes the primary {@link RateLimiter} read {@link LimiterClock}. Imported by {@link WebIntegrationTest}. */
@TestConfiguration(proxyBeanMethods = false)
public class LimiterClockConfiguration {

    @Bean
    @Primary
    RateLimiter limiterClockRateLimiter(RateLimitCounterRepository repository, KeyHasher keyHasher,
            MeterRegistry meters) {
        return new RateLimiter(repository, keyHasher, LimiterClock.INSTANCE, meters);
    }
}
