package com.matheusmelo.kybr.service.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler method as idempotent under {@code Idempotency-Key}. The check runs
 * in {@code IdempotencyInterceptor} before argument resolution and validation, so a replay beats
 * validation and any business error.
 *
 * <p>Every handler declares how it completes ({@link #completion()}, no default): through
 * {@link IdempotencyService#completeInTransaction} (attempt in the request attribute
 * {@code IdempotencyInterceptor.ATTEMPT_ATTRIBUTE}), or by keeping the key on its primary document
 * under a unique index. A misdeclared handler fails startup or, at runtime, its request.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    IdempotencyProfile profile();

    /** The bean that resolves the binding (slug, workspace, or a constant such as {@code signup}). */
    Class<? extends IdempotencyBindingResolver> binding();

    /** How a successful attempt completes. Required, so the choice is made at every endpoint. */
    IdempotencyCompletion completion();

    /**
     * The collection holding the key under a unique {@code idempotency_key} index; required with
     * {@link IdempotencyCompletion#UNIQUE_KEY}, ignored otherwise.
     */
    String uniqueKeyCollection() default "";

    /**
     * A counter tagged {@code slug} and {@code resultado} that also counts this handler's replays
     * ({@code replayed}) and refusals, any 4xx ({@code rejected}); empty for none. A replay answers
     * before the link is resolved, so it is tagged with the unknown slug.
     */
    String outcomeMetric() default "";
}
