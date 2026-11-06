package com.matheusmelo.kybr.dto.validation;

import com.matheusmelo.kybr.util.Cnpj;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Backs {@link CnpjCanonico} with {@link Cnpj#isValidCanonical}; never canonicalises (the wire rejects it). */
public class CnpjCanonicoValidator implements ConstraintValidator<CnpjCanonico, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || Cnpj.isValidCanonical(value);
    }
}
