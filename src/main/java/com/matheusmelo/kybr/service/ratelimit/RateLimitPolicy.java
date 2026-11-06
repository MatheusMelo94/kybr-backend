package com.matheusmelo.kybr.service.ratelimit;

import com.matheusmelo.kybr.exception.ErrorCode;
import java.time.Duration;
import java.util.Objects;

/**
 * An immutable fixed-window limit. Declared as a bean by the milestone that needs
 * it; B0 ships only test policies.
 *
 * @param name unique policy name; part of the counter id and the metric tag (no {@code ':'})
 * @param limit calls allowed per window (≥ 1)
 * @param window window length, a whole number of seconds (≥ 1 s)
 * @param errorCode the 429 code a denial renders as
 * @param subjectKind the only subject kind this policy accepts
 */
public record RateLimitPolicy(String name, int limit, Duration window, ErrorCode errorCode, SubjectKind subjectKind) {

    public RateLimitPolicy {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(subjectKind, "subjectKind");
        if (name.isBlank() || name.indexOf(':') >= 0) {
            throw new IllegalArgumentException("policy name must be non-blank and contain no ':'");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (window.toSeconds() < 1 || window.toNanosPart() != 0) {
            throw new IllegalArgumentException("window must be a whole, positive number of seconds");
        }
        if (errorCode.status().value() != 429) {
            throw new IllegalArgumentException(errorCode + " is not a 429 code");
        }
    }
}
