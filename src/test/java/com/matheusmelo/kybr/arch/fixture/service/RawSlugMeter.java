package com.matheusmelo.kybr.arch.fixture.service;

import io.micrometer.core.instrument.MeterRegistry;

/** Rule fixture, never run: a meter tagged with a slug taken straight from a request. */
public class RawSlugMeter {

    MeterRegistry meters;

    void count(String requestSlug) {
        meters.counter("fixture.lookup", "slug", requestSlug).increment();
    }
}
