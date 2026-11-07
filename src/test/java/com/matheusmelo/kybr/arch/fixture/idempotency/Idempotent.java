package com.matheusmelo.kybr.arch.fixture.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Rule fixture: an idempotency marker whose completion has a default, so a handler can omit it. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    String completion() default "TRANSACTIONAL";
}
