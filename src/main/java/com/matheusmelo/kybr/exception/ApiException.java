package com.matheusmelo.kybr.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Base of every exception rendered as the error envelope. One handler renders all subclasses,
 * so later milestones add exceptions without touching it.
 *
 * <p>The exception message is the code name only: never input, ids or tokens.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final String field;
    private final Map<String, String> headers;

    public ApiException(ErrorCode code) {
        this(code, null);
    }

    public ApiException(ErrorCode code, String field) {
        this(code, field, Map.of(), null);
    }

    protected ApiException(ErrorCode code, String field, Map<String, String> headers, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
        this.field = field;
        this.headers = Map.copyOf(headers);
    }

    public ErrorCode code() {
        return code;
    }

    /** The offending field (snake_case JSON name or header name), or null. */
    public String field() {
        return field;
    }

    /** Response headers to send with the envelope, e.g. {@code Retry-After}. */
    public Map<String, String> headers() {
        return headers;
    }

    public HttpStatus status() {
        return code.status();
    }
}
