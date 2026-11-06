package com.matheusmelo.kybr.dto.validation;

import com.matheusmelo.kybr.exception.ErrorCode;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A canonical CNPJ (wire policy "reject"): anything {@link
 * com.matheusmelo.kybr.util.Cnpj#isValidCanonical} refuses is a violation. Null is valid; pair
 * with {@code @NotNull} when the value is required.
 *
 * <p><b>Rendering contract (for the exception handler):</b> the violation message is the
 * {@link ErrorCode} name {@value #CODE_NAME}, also exposed as {@link #CODE}. It renders as
 * {@code CNPJ_INVALIDO} with {@code field} set, at the status {@code LocationStatusTable} gives
 * for where the value came from (422 in a body; 400 on a query or path parameter).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD,
        ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CnpjCanonicoValidator.class)
public @interface CnpjCanonico {

    /** The error code a violation renders as. */
    ErrorCode CODE = ErrorCode.CNPJ_INVALIDO;

    /** {@link #CODE}'s name, usable in annotation attributes. */
    String CODE_NAME = "CNPJ_INVALIDO";

    String message() default CODE_NAME;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
