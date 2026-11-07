package com.matheusmelo.kybr.dto.link;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * E03: the new case's token, the seller's only way to read the case.
 *
 * @param token opaque, 22 to 64 characters of {@code [A-Za-z0-9_-]}
 */
public record VerifyResponse(
        @Schema(description = "Opaque; the seller's only way to read the case. Keep it secret.",
                pattern = "^[A-Za-z0-9_-]{22,64}$", requiredMode = Schema.RequiredMode.REQUIRED) String token) {

    @Override
    public String toString() {
        return "VerifyResponse[redacted]";
    }
}
