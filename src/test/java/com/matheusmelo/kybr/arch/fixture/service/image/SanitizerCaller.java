package com.matheusmelo.kybr.arch.fixture.service.image;

import com.matheusmelo.kybr.arch.fixture.sanitizer.SanitizerFixture;

/** Violates the sanitizer dependency rule: an API-side class reaching into the sanitizer. */
public class SanitizerCaller {

    public Object call() {
        return new SanitizerFixture();
    }
}
