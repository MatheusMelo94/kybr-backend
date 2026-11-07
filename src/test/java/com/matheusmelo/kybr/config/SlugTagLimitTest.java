package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.matheusmelo.kybr.service.MetricTags;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SlugTagLimitTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    SlugTagLimitTest() {
        registry.config().meterFilter(new SlugTagLimit(3));
    }

    private void count(String slug) {
        registry.counter("kybr.test.calls", MetricTags.SLUG, slug).increment();
    }

    private Set<String> slugs() {
        return registry.getMeters().stream().map(Meter::getId).map(id -> id.getTag(MetricTags.SLUG))
                .collect(Collectors.toSet());
    }

    @Test
    void slugsBeyondTheCapShareOneOverflowValueWithOneWarning() {
        try (CapturedEvents log = CapturedEvents.of(SlugTagLimit.class)) {
            count("loja-a");
            count("loja-b");
            count("loja-c");
            count("loja-d");
            count("loja-e");
            count("loja-a");

            assertThat(slugs()).containsExactlyInAnyOrder("loja-a", "loja-b", "loja-c", SlugTagLimit.OVERFLOW);
            assertThat(registry.counter("kybr.test.calls", MetricTags.SLUG, SlugTagLimit.OVERFLOW).count())
                    .isEqualTo(2.0);
            assertThat(registry.counter("kybr.test.calls", MetricTags.SLUG, "loja-a").count()).isEqualTo(2.0);
            assertThat(log.events()).hasSize(1);
            assertThat(log.events().getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(log.events().getFirst().getFormattedMessage()).contains("event=metric_slug_overflow");
        }
    }

    @Test
    void theReservedValuesNeverCountAgainstTheCap() {
        count(MetricTags.UNKNOWN);
        count(SlugTagLimit.OVERFLOW);
        count("loja-a");
        count("loja-b");
        count("loja-c");

        assertThat(slugs()).containsExactlyInAnyOrder(MetricTags.UNKNOWN, SlugTagLimit.OVERFLOW, "loja-a", "loja-b",
                "loja-c");
    }

    @Test
    void metersWithoutASlugTagAreUntouched() {
        registry.counter("kybr.test.other", "resultado", "ok").increment();

        assertThat(registry.find("kybr.test.other").tag("resultado", "ok").counter()).isNotNull();
    }
}
