package com.matheusmelo.kybr.service.upload;

import com.matheusmelo.kybr.model.document.SituacaoEnvio;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;

/**
 * An upload received for verification (or a retry of one).
 *
 * @param documentosEnviados the type's files accepted or still being verified
 * @param situacao the type's verification state: {@code verificando} after new files
 */
public record UploadOutcome(DocumentoTipo tipo, int documentosEnviados, SituacaoEnvio situacao) {
}
