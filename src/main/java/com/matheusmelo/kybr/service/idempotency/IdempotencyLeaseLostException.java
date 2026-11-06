package com.matheusmelo.kybr.service.idempotency;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import java.util.Map;

/**
 * A fenced write matched no document: another attempt took the lease over. The business
 * transaction is rolled back; 503 {@code INDISPONIVEL} with {@code Retry-After: 1}, and the retry
 * replays the winner's result.
 */
public class IdempotencyLeaseLostException extends ApiException {

    public IdempotencyLeaseLostException() {
        super(ErrorCode.INDISPONIVEL, null, Map.of("Retry-After", "1"), null);
    }
}
