package com.matheusmelo.kybr.config;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Meter filters applied to every registry: the cap on distinct {@code slug} tag values. */
@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

    @Bean
    MeterFilter slugTagLimit(KybrProperties properties) {
        return new SlugTagLimit(properties.metrics().maxSlugTags());
    }
}
