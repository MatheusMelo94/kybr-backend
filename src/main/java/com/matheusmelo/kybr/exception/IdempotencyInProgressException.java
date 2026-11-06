package com.matheusmelo.kybr.exception;

import java.util.Map;

/** The first request with this {@code Idempotency-Key} is still running; retry in a second. */
public class IdempotencyInProgressException extends ApiException {

    public IdempotencyInProgressException() {
        super(ErrorCode.INDISPONIVEL, null, Map.of("Retry-After", "1"), null);
    }
}
