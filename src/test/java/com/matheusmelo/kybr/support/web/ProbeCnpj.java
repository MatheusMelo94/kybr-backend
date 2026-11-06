package com.matheusmelo.kybr.support.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-only stand-in for {@code @CnpjCanonico}: same error-code contract (the message
 * template is the {@code ErrorCode} name), shape check only (no check digits).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ProbeCnpj.Validator.class)
public @interface ProbeCnpj {

    String message() default "CNPJ_INVALIDO";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ProbeCnpj, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.matches("^[0-9A-Z]{12}[0-9]{2}$");
        }
    }
}
