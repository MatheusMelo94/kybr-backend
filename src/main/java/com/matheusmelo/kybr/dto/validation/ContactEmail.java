package com.matheusmelo.kybr.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A seller's contact e-mail, after trimming ({@link ContactEmailValidator#normalize}): at most
 * {@value ContactEmailValidator#MAX_LENGTH} characters, no control or format character, no
 * whitespace, exactly one {@code @} with a non-empty local part, and a domain containing a dot.
 * {@code <>"} are accepted in the local part. Null is valid here; {@link ContactEmailValidator#isValid}
 * refuses it for code that validates after a lookup. A violation renders as {@code EMAIL_INVALIDO}.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD,
        ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ContactEmailValidator.class)
public @interface ContactEmail {

    String message() default "EMAIL_INVALIDO";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
