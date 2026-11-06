package com.matheusmelo.kybr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.matheusmelo.kybr.exception.ErrorCode;

/**
 * The error envelope: {@code {code, message}} or {@code {code, message, field}}.
 * No timestamp, status or path. Code-specific extras (B1+) go through a typed subclass.
 */
public record ErrorResponse(ErrorCode code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) String field) {

    public static ErrorResponse of(ErrorCode code) {
        return of(code, null);
    }

    public static ErrorResponse of(ErrorCode code, String field) {
        return new ErrorResponse(code, code.message(), field);
    }
}
