package com.matheusmelo.kybr.exception;

/** The same {@code Idempotency-Key} was reused with a different request. */
public class IdempotencyMismatchException extends ApiException {

    public IdempotencyMismatchException() {
        super(ErrorCode.VALOR_INVALIDO, "Idempotency-Key");
    }
}
