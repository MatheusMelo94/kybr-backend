package com.matheusmelo.kybr.dto.link;

import com.matheusmelo.kybr.dto.validation.ContactEmail;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * E05: the seller's contact e-mail for this case. Validated after the case is found (so an unknown
 * case answers 404 first); the annotation documents the rule and gives a wrongly typed value its
 * code.
 *
 * @param email at most 254 characters, no control or format characters, one {@code @}, a dotted
 *     domain; surrounding spaces are trimmed
 */
public record ContactRequest(
        @Schema(description = "The seller's contact e-mail for this case: at most 254 characters, no control or "
                + "format characters, one `@`, a dotted domain. Surrounding spaces are trimmed. Not confirmed.",
                maxLength = 254, example = "joao@empresa.com.br", requiredMode = Schema.RequiredMode.REQUIRED)
        @ContactEmail String email) {

    @Override
    public String toString() {
        return "ContactRequest[redacted]";
    }
}
