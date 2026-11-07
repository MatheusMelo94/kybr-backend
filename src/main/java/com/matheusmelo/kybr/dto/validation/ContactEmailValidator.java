package com.matheusmelo.kybr.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/**
 * Backs {@link ContactEmail}, and validates in code where the order of checks requires it (after a
 * case lookup).
 */
public class ContactEmailValidator implements ConstraintValidator<ContactEmail, String> {

    public static final int MAX_LENGTH = 254;

    /** Control, format and whitespace characters, Unicode-aware. */
    private static final Pattern FORBIDDEN = Pattern.compile("[\\p{Cc}\\p{Cf}\\s\\p{Z}]");

    /** The stored form: trimmed. Null-safe. */
    public static String normalize(String value) {
        return value == null ? null : value.strip();
    }

    /** True when the trimmed value satisfies every rule; null is invalid here. */
    public static boolean isValid(String value) {
        String email = normalize(value);
        if (email == null || email.isEmpty() || email.length() > MAX_LENGTH || FORBIDDEN.matcher(email).find()) {
            return false;
        }
        int at = email.indexOf('@');
        if (at <= 0 || email.indexOf('@', at + 1) >= 0) {
            return false;
        }
        String domain = email.substring(at + 1);
        return domain.indexOf('.') > 0;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || isValid(value);
    }
}
