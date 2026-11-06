package com.matheusmelo.kybr.exception;

import java.util.Map;

/** 429 with the policy's code and {@code Retry-After} in whole seconds. */
public class RateLimitExceededException extends ApiException {

    public RateLimitExceededException(ErrorCode code, long retryAfterSeconds) {
        super(requireTooManyRequests(code), null,
                Map.of("Retry-After", Long.toString(Math.max(1, retryAfterSeconds))), null);
    }

    private static ErrorCode requireTooManyRequests(ErrorCode code) {
        if (code.status().value() != 429) {
            throw new IllegalArgumentException(code + " is not a 429 code");
        }
        return code;
    }
}
