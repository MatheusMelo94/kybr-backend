package com.matheusmelo.kybr.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The person responsible for a company, as the seller typed it. After NFC normalisation and
 * trimming ({@link ResponsavelValidator#normalize}): at most {@value ResponsavelValidator#MAX_CODE_POINTS}
 * code points; only letters (each with at most two combining marks), space, {@code '}, {@code -}
 * and {@code .}; no Hangul filler characters; at least two words with a letter each. Null is valid;
 * pair with {@code @NotNull(message = "RESPONSAVEL_INVALIDO")} when required.
 *
 * <p>A violation renders as {@code RESPONSAVEL_INVALIDO} with {@code field} (the message is the
 * code name).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD,
        ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ResponsavelValidator.class)
public @interface Responsavel {

    String message() default "RESPONSAVEL_INVALIDO";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
