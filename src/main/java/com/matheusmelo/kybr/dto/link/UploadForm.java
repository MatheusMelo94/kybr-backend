package com.matheusmelo.kybr.dto.link;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * E06's {@code multipart/form-data} body, for the contract only: the controller hands the body to
 * the upload service unparsed, and never binds it to this type.
 *
 * @param tipo the document type the files are for
 * @param arquivos the files; their names and declared types are ignored
 */
@Schema(description = "One `tipo` and one or more `arquivos` (the field repeated once per file).")
public record UploadForm(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ResultadoPublico.DocumentoTipo tipo,
        @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "One PDF, or one or more JPEG or PNG photos. Typed by their first bytes; the file "
                        + "name and the part's content type are ignored."),
                schema = @Schema(type = "string", format = "binary"), minItems = 1, maxItems = 30)
        List<byte[]> arquivos) {
}
