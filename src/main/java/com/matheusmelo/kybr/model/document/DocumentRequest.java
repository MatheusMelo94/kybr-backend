package com.matheusmelo.kybr.model.document;

import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A scoped capability to send more documents for a case: upload-only, for the listed types, until
 * it expires, is revoked or is closed. The seller holds the token; only its SHA-256 is stored, like
 * the case token.
 *
 * @param tipos the document types it may send
 * @param revogadoEm set when a newer request replaces it
 * @param encerradoEm set when the case is decided
 */
@Document(DocumentRequest.COLLECTION)
public record DocumentRequest(
        @Id UUID id,
        @Field("workspace_id") UUID workspaceId,
        @Field("case_id") UUID caseId,
        @Field("token_hash") String tokenHash,
        @Field("tipos") List<DocumentoTipo> tipos,
        @Field("criado_em") Instant criadoEm,
        @Field("expira_em") Instant expiraEm,
        @Field("revogado_em") Instant revogadoEm,
        @Field("encerrado_em") Instant encerradoEm) {

    public static final String COLLECTION = "document_requests";
    public static final String CASE_ID = "case_id";
    public static final String TOKEN_HASH = "token_hash";
    public static final String EXPIRA_EM = "expira_em";
    public static final String REVOGADO_EM = "revogado_em";
    public static final String ENCERRADO_EM = "encerrado_em";

    public DocumentRequest {
        tipos = List.copyOf(Objects.requireNonNullElse(tipos, List.of()));
    }
}
