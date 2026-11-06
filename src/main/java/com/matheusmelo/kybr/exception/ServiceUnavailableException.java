package com.matheusmelo.kybr.exception;

import java.util.Map;

/** A dependency (MongoDB) is unreachable; fail closed with 503. */
public class ServiceUnavailableException extends ApiException {

    public ServiceUnavailableException(Throwable cause) {
        super(ErrorCode.INDISPONIVEL, null, Map.of(), cause);
    }
}
