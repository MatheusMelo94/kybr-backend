package com.matheusmelo.kybr.exception;

import org.springframework.http.HttpStatus;

/**
 * A request value rejected in code rather than by bean validation (so other checks can run first).
 * Its status comes from {@link LocationStatusTable} for where the value came from, as for a
 * validation failure: {@code CNPJ_INVALIDO} in a query parameter is 400.
 */
public class InvalidParameterException extends ApiException {

    private final HttpStatus status;

    public InvalidParameterException(ErrorCode code, String field, ParameterLocation location) {
        super(code, field);
        this.status = LocationStatusTable.resolve(code, location);
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
