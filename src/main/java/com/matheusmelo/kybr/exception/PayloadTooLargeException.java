package com.matheusmelo.kybr.exception;

/** The request body is over its route limit. */
public class PayloadTooLargeException extends ApiException {

    public PayloadTooLargeException() {
        super(ErrorCode.REQUISICAO_GRANDE);
    }
}
