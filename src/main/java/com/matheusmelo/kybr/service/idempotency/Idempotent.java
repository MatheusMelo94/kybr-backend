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
 * <p>Rule for B1+: the endpoint either completes through
 * {@link IdempotencyService#completeInTransaction} (attempt in the request attribute
 * {@code IdempotencyInterceptor.ATTEMPT_ATTRIBUTE}) or keeps the key on its primary document under
 * a unique index.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    IdempotencyProfile profile();

    /** The bean that resolves the binding (slug, workspace, or a constant such as {@code signup}). */
    Class<? extends IdempotencyBindingResolver> binding();
}
