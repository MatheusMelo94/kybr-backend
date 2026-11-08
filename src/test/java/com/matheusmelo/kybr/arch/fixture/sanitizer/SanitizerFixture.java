package com.matheusmelo.kybr.arch.fixture.sanitizer;

import org.springframework.context.annotation.Configuration;

/**
 * Holds a violation of the sanitizer scan rule. The stereotype sits on a non-static inner class,
 * which component scanning never registers, so no test context picks it up.
 */
public class SanitizerFixture {

    @Configuration
    public class Scanned {
    }
}
