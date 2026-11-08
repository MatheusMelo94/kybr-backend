package com.matheusmelo.kybr.exception;

import java.util.Map;

/**
 * 503 {@code INDISPONIVEL} with {@code Retry-After}: this machine or a dependency can't take the
 * request now, and nothing was stored. The cause, if any, is logged by type only.
 */
public class RetryLaterException extends ApiException {

    public RetryLaterException(long retryAfterSeconds) {
        this(retryAfterSeconds, null);
    }

    public RetryLaterException(long retryAfterSeconds, Throwable cause) {
        super(ErrorCode.INDISPONIVEL, null, Map.of("Retry-After", Long.toString(Math.max(1, retryAfterSeconds))), cause);
    }
}
