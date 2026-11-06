package com.matheusmelo.kybr.service.idempotency;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import java.util.Map;

/**
 * A stored result can't be replayed (its body doesn't decrypt): 500 {@code ERRO_INTERNO}.
 * The handler is never re-executed, since the first execution already happened.
 */
public class IdempotencyReplayFailedException extends ApiException {

    public IdempotencyReplayFailedException(Throwable cause) {
        super(ErrorCode.ERRO_INTERNO, null, Map.of(), cause);
    }
}
