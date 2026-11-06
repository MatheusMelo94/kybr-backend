package com.matheusmelo.kybr.service.ratelimit;

/**
 * The outcome of a rate-limit check.
 *
 * @param allowed whether this call is within the limit
 * @param remaining calls left in the current window (never negative)
 * @param retryAfterSeconds when denied, whole seconds to the window end (≥ 1); 0 when allowed
 */
public record RateLimitDecision(boolean allowed, long remaining, long retryAfterSeconds) {
}
