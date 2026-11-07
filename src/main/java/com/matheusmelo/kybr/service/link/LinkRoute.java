package com.matheusmelo.kybr.service.link;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler under {@code /public/v/{slug}}: the workspace is resolved from the {@code slug}
 * path variable before the handler's arguments are bound, and handed to it as a request attribute.
 * An unknown, off-pattern or not-onboarded slug is 404 {@code LINK_NAO_ENCONTRADO}, decided from the
 * slug alone.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LinkRoute {

    /**
     * True only for reading an existing case: a disabled link still serves it. Every other route
     * answers 404 {@code LINK_NAO_ENCONTRADO} for a disabled link.
     */
    boolean readableWhenDisabled() default false;
}
