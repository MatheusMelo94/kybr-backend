package com.matheusmelo.kybr.dto.link;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * E04: a case's state and its seller-facing result.
 *
 * @param estado what the seller sees
 * @param criadoEm when the case was created, ISO 8601 with offset
 * @param resultado required unless {@code estado} is {@code processing}; on {@code processing}
 *     only after a contact e-mail was saved or when no company data was found
 */
@Schema(description = "A case's state and its seller-facing result. `resultado` is present unless `estado` is "
        + "`processing`; on `processing` it appears only once a contact e-mail was saved (with `responsavel` and "
        + "`email_contato_mascarado`) or when no company data was found.")
public record CaseResponse(
        @Schema(description = "`processing` while the verification runs; then `pending` (something is needed "
                + "from the seller or a review is under way), `approved` or `blocked`.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Estado estado,
        @Schema(description = "When the case was created, ISO 8601 with an offset.", example = "2026-11-07T14:03:11Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant criadoEm,
        @JsonInclude(JsonInclude.Include.NON_NULL) ResultadoPublico resultado) {

    /** The constants are the wire values. */
    public enum Estado {
        processing,
        pending,
        approved,
        blocked
    }
}
