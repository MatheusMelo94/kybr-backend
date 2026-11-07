package com.matheusmelo.kybr.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Printable ASCII ({@code 0x20}–{@code 0x7E}), 1 to {@link #max()} characters: no control and no
 * non-ASCII character. Null is valid (the value is optional). A violation renders as
 * {@code VALOR_INVALIDO} with {@code field}.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD,
        ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PrintableAsciiValidator.class)
public @interface PrintableAscii {

    /** The most characters allowed. */
    int max();

    String message() default "VALOR_INVALIDO";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
