package com.matheusmelo.kybr.model.document;

import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One file of a seller's document: a PDF, or one page (photo) of it. A row is written when the file
 * is received into quarantine, and it is the scan job's unit of work (claimed with a lease) until the
 * file is accepted or refused.
 *
 * <p>There is no field for the file's original name, on purpose: it is never read into anything
 * stored or logged.
 *
 * @param documentRequestId the document request it answered; null when sent through the link
 * @param pagina 1 for a PDF; the page number for a photo
 * @param storageKey the final object key ({@code workspace/case/document}), stored as {@code s3_key};
 *     null until accepted
 * @param contentType from the file's first bytes, never from the request
 * @param bytesOriginal what the seller sent; the per-type limit counts these
 * @param bytesArmazenados what was stored (a photo is re-encoded, so it differs); null until accepted
 * @param sha256Original lower-case hex SHA-256 of what the seller sent; unique per case and type
 *     among the files that aren't refused, so a retried file is never kept twice
 * @param sha256 lower-case hex SHA-256 of the stored bytes; null until accepted
 * @param estado where the file is in its verification
 * @param motivoRecusa why it was refused; null unless {@code RECUSADO}
 * @param quarentenaKey the quarantine object key; null once that object is gone
 * @param envioId the upload request it came in, so the type's latest upload can be told apart
 * @param ativo true while the file counts (received, being verified or accepted), unset once refused;
 *     the duplicate guard's unique index covers only these
 * @param tentativas scan attempts so far
 * @param proximaTentativa when the scan job may claim it next
 * @param leaseOwner the scan worker holding the claim; null when unclaimed
 * @param leaseUntil when another worker may take the claim over
 */
@org.springframework.data.mongodb.core.mapping.Document(Document.COLLECTION)
public record Document(
        @Id UUID id,
        @Field("workspace_id") UUID workspaceId,
        @Field("case_id") UUID caseId,
        @Field("document_request_id") UUID documentRequestId,
        @Field("tipo") DocumentoTipo tipo,
        @Field("formato") FormatoEnvio formato,
        @Field("pagina") int pagina,
        @Field("s3_key") String storageKey,
        @Field("content_type") String contentType,
        @Field("bytes_original") long bytesOriginal,
        @Field("bytes_armazenados") Long bytesArmazenados,
        @Field("sha256_original") String sha256Original,
        @Field("sha256") String sha256,
        @Field("criado_em") Instant criadoEm,
        @Field("estado") EstadoDocumento estado,
        @Field("motivo_recusa") MotivoRecusa motivoRecusa,
        @Field("quarentena_key") String quarentenaKey,
        @Field("envio_id") UUID envioId,
        @Field("ativo") Boolean ativo,
        @Field("tentativas") Integer tentativas,
        @Field("proxima_tentativa") Instant proximaTentativa,
        @Field("lease_owner") String leaseOwner,
        @Field("lease_until") Instant leaseUntil) {

    public static final String COLLECTION = "documents";
    public static final String CASE_ID = "case_id";
    public static final String TIPO = "tipo";
    public static final String PAGINA = "pagina";
    public static final String STORAGE_KEY = "s3_key";
    public static final String BYTES_ARMAZENADOS = "bytes_armazenados";
    public static final String SHA256_ORIGINAL = "sha256_original";
    public static final String SHA256 = "sha256";
    public static final String CRIADO_EM = "criado_em";
    public static final String ESTADO = "estado";
    public static final String MOTIVO_RECUSA = "motivo_recusa";
    public static final String QUARENTENA_KEY = "quarentena_key";
    public static final String ENVIO_ID = "envio_id";
    public static final String ATIVO = "ativo";
    public static final String TENTATIVAS = "tentativas";
    public static final String PROXIMA_TENTATIVA = "proxima_tentativa";
    public static final String LEASE_OWNER = "lease_owner";
    public static final String LEASE_UNTIL = "lease_until";

    public Document {
        tentativas = tentativas == null ? 0 : tentativas;
    }

    /** A file already accepted and stored (records written before the scan job, and test data). */
    public Document(UUID id, UUID workspaceId, UUID caseId, UUID documentRequestId, DocumentoTipo tipo,
            FormatoEnvio formato, int pagina, String storageKey, String contentType, long bytesOriginal,
            long bytesArmazenados, String sha256Original, String sha256, Instant criadoEm) {
        this(id, workspaceId, caseId, documentRequestId, tipo, formato, pagina, storageKey, contentType,
                bytesOriginal, bytesArmazenados, sha256Original, sha256, criadoEm, EstadoDocumento.ACEITO, null,
                null, null, true, 0, null, null, null);
    }

    /** A file just received into quarantine, due for its first scan at {@code criadoEm}. */
    public static Document recebido(UUID id, UUID workspaceId, UUID caseId, UUID documentRequestId,
            DocumentoTipo tipo, FormatoEnvio formato, int pagina, String contentType, long bytesOriginal,
            String sha256Original, Instant criadoEm, String quarentenaKey, UUID envioId) {
        return new Document(id, workspaceId, caseId, documentRequestId, tipo, formato, pagina, null, contentType,
                bytesOriginal, null, sha256Original, null, criadoEm, EstadoDocumento.RECEBIDO, null, quarentenaKey,
                envioId, true, 0, criadoEm, null, null);
    }

    /** The state, reading a record without one (written before the scan job existed) as accepted. */
    public EstadoDocumento estadoOuAceito() {
        return estado == null ? EstadoDocumento.ACEITO : estado;
    }
}
