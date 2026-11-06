package com.matheusmelo.kybr.service.idempotency;

import java.util.Map;

/**
 * Resolves what an idempotency key is scoped to: the record id is
 * {@code hmac(profile|binding|key)}, so the same key under another binding is independent.
 * Implementations are Spring beans named by {@link Idempotent#binding()}.
 */
public interface IdempotencyBindingResolver {

    /**
     * @param uriVariables the matched route's URI template variables
     * @return a non-blank binding
     */
    String resolve(Map<String, String> uriVariables);
}
