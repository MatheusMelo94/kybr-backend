package com.matheusmelo.kybr.service.idempotency;

/** How an {@link Idempotent} handler finishes a successful attempt, so it can never execute twice. */
public enum IdempotencyCompletion {

    /**
     * The handler finishes every 2xx attempt through {@link IdempotencyService#completeInTransaction},
     * so a lost lease rolls its business write back. A 2xx left unfinished is caught at runtime.
     */
    TRANSACTIONAL,

    /**
     * The handler stores the normalised key on its primary document under a unique index on
     * {@code idempotency_key}, so a second execution can't commit; the attempt completes after the
     * response. {@link Idempotent#uniqueKeyCollection()} names the collection, checked at startup.
     */
    UNIQUE_KEY
}
