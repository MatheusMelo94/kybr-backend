package com.matheusmelo.kybr.config;

import com.mongodb.client.MongoClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.mongodb.health.MongoHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Health wiring. The probe endpoints themselves are configured in
 * {@code application.yml}: {@code /livez} on the main port (liveness, status only, no MongoDB),
 * readiness and Prometheus on the management port only.
 *
 * <p>The MongoDB check behind readiness is cached for 2 s, so frequent probes never flood
 * MongoDB. Naming the bean {@code mongoHealthContributor} replaces Boot's own and keeps the
 * {@code mongo} contributor name the readiness group includes.
 */
@Configuration(proxyBeanMethods = false)
public class HealthConfig {

    static final Duration MONGO_CHECK_TTL = Duration.ofSeconds(2);

    @Bean
    CachedHealthIndicator mongoHealthContributor(MongoClient mongoClient, Clock clock) {
        return new CachedHealthIndicator(new MongoHealthIndicator(mongoClient), MONGO_CHECK_TTL, clock);
    }
}
