package com.matheusmelo.kybr.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application {@link Clock} (UTC), injected wherever time decides an outcome (rate-limit
 * windows, idempotency leases and TTLs), so tests replace it with a fixed or movable
 * {@code @Primary} clock.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
