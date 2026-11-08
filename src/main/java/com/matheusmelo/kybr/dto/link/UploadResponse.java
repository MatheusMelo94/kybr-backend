package com.matheusmelo.kybr.dto.link;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * E06: the document type, how many of its files count, and its verification state.
 *
 * @param documentosEnviados the type's files accepted or still being checked, across calls
 * @param situacaoEnvio {@code verificando} after new files; a retry that adds nothing answers the
 *     type's current state
 */
public record UploadResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ResultadoPublico.DocumentoTipo tipo,
        @Schema(description = "The type's files accepted or still being checked, across calls; a retried file "
                + "isn't counted twice, and refused files aren't counted.", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) int documentosEnviados,
        @Schema(description = "`verificando` once new files were received: they are checked in the background, "
                + "and the case read (D4) shows the outcome. A retry that adds nothing answers the type's current "
                + "state.", requiredMode = Schema.RequiredMode.REQUIRED) ResultadoPublico.SituacaoEnvio situacaoEnvio) {
}
