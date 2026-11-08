package com.matheusmelo.kybr.model.link;

import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import java.util.List;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * An upload's audit details: the document type, each file's hashes and sizes, and, for a refused
 * upload, the scanner's signature name. Never a file name or any file content.
 *
 * @param assinatura the scanner's signature name, with {@code DOCUMENTO_RECUSADO} for a detection
 */
public record ResumoDocumento(
        @Field("tipo") DocumentoTipo tipo,
        @Field("arquivos") List<Arquivo> arquivos,
        @Field("assinatura") String assinatura) {

    public ResumoDocumento {
        arquivos = arquivos == null ? List.of() : List.copyOf(arquivos);
    }

    /**
     * One file.
     *
     * @param sha256Original lower-case hex SHA-256 of what the seller sent
     * @param sha256 lower-case hex SHA-256 of what was stored; null when nothing was
     * @param bytesOriginal what the seller sent
     * @param bytesArmazenados what was stored; null when nothing was
     */
    public record Arquivo(
            @Field("sha256_original") String sha256Original,
            @Field("sha256") String sha256,
            @Field("bytes_original") long bytesOriginal,
            @Field("bytes_armazenados") Long bytesArmazenados) {
    }
}
