package com.matheusmelo.kybr.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Backs {@link PrintableAscii}: 1 to max characters, each in {@code 0x20}–{@code 0x7E}. */
public class PrintableAsciiValidator implements ConstraintValidator<PrintableAscii, String> {

    private int max;

    @Override
    public void initialize(PrintableAscii annotation) {
        max = annotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.isEmpty() || value.length() > max) {
            return false;
        }
        return value.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
    }
}
