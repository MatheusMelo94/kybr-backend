package com.matheusmelo.kybr.arch.fixture.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/** Rule fixture, never run: every way of producing a conditional (304-capable) response. */
public class ConditionalResponder {

    ShallowEtagHeaderFilter etagFilter;

    ResponseEntity<String> tagged() {
        return ResponseEntity.ok().eTag("\"v1\"").body("x");
    }

    ResponseEntity<String> dated() {
        return ResponseEntity.ok().lastModified(0L).body("x");
    }

    boolean notModified(WebRequest request) {
        return request.checkNotModified("\"v1\"");
    }
}
