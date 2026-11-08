package com.matheusmelo.kybr.model.link;

import com.matheusmelo.kybr.model.workspace.Politica;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One seller's verification through a workspace's link. The seller holds the case token; the case
 * stores only its SHA-256 ({@code tokenHash}), unkeyed on purpose: the token carries 256 random
 * bits, and a keyed hash would orphan every token when the hash secret rotates.
 *
 * @param estado what the seller sees
 * @param cnpj canonical
 * @param responsavel the person the seller named, NFC-normalised. Untrusted seller input
 * @param ref the integrator's reference, stored untouched and never echoed. Untrusted seller input
 * @param extId the integrator's external id, "informado pelo link, não verificado": never verified,
 *     always labelled so. Untrusted seller input
 * @param politica a copy of the workspace's policy (rules and version) when the case was created,
 *     so a later policy change is never retroactive
 * @param resultado the stored seller-facing projection, once there is a verdict or a variant
 * @param verificacaoId the verification that produced {@code resultado}
 * @param prazoRevisao when a pending case should be decided
 * @param idempotencyKey the normalised key of the request that created the case, for support
 * @param retencaoAte when the case's documents and contact are due for deletion: set when it's
 *     decided, or as a backstop on a case never decided
 * @param documentos per document type, what the case holds (empty before any upload)
 * @param arquivosTotal files stored across document types
 * @param tokenRevogadoEm when the case token stopped working (retention); a revoked token reads as
 *     an unknown one
 * @param retencao the retention run's state; null before one is claimed
 * @param retencaoOrigem where {@code retencaoAte} came from
 *
 * <p>Cases stored before documents existed lack the last five fields: they read as no documents,
 * zero files and nulls.
 */
@Document(LinkCase.COLLECTION)
public record LinkCase(
        @Id UUID id,
        @Field("workspace_id") UUID workspaceId,
        @Field("token_hash") String tokenHash,
        @Field("estado") EstadoCaso estado,
        @Field("criado_em") Instant criadoEm,
        @Field("cnpj") String cnpj,
        @Field("responsavel") String responsavel,
        @Field("ref") String ref,
        @Field("ext_id") String extId,
        @Field("politica") Politica politica,
        @Field("variante") Variante variante,
        @Field("resultado") Resultado resultado,
        @Field("verificacao_id") UUID verificacaoId,
        @Field("contato") Contato contato,
        @Field("processamento") Processamento processamento,
        @Field("prazo_revisao") Instant prazoRevisao,
        @Field("revisao") Revisao revisao,
        @Field("idempotency_key") String idempotencyKey,
        @Field("retencao_ate") Instant retencaoAte,
        @Field("documentos") Map<DocumentoTipo, DocumentosTipo> documentos,
        @Field("arquivos_total") Integer arquivosTotal,
        @Field("token_revogado_em") Instant tokenRevogadoEm,
        @Field("retencao") Retencao retencao,
        @Field("retencao_origem") OrigemRetencao retencaoOrigem) {

    public static final String COLLECTION = "cases";
    public static final String WORKSPACE_ID = "workspace_id";
    public static final String TOKEN_HASH = "token_hash";
    public static final String ESTADO = "estado";
    public static final String CNPJ = "cnpj";
    public static final String CRIADO_EM = "criado_em";
    public static final String PROCESSAMENTO = "processamento";
    public static final String PROCESSAMENTO_ESTADO = "processamento.estado";
    public static final String PROCESSAMENTO_PROXIMA_TENTATIVA = "processamento.proxima_tentativa";
    public static final String PROCESSAMENTO_LEASE_OWNER = "processamento.lease_owner";
    public static final String PROCESSAMENTO_LEASE_UNTIL = "processamento.lease_until";
    public static final String CONTATO = "contato";
    public static final String RESULTADO = "resultado";
    public static final String RETENCAO_ATE = "retencao_ate";
    public static final String DOCUMENTOS = "documentos";
    public static final String ARQUIVOS_TOTAL = "arquivos_total";
    /**
     * When an upload or the scan job last changed the case's documents. Written, never read: the
     * write makes those changes conflict with retention's claim and with each other, so they
     * serialise (a read wouldn't).
     */
    public static final String DOCUMENTOS_ATUALIZADO_EM = "documentos_atualizado_em";
    public static final String TOKEN_REVOGADO_EM = "token_revogado_em";
    public static final String RETENCAO = "retencao";
    public static final String RETENCAO_ESTADO = "retencao.estado";
    public static final String RETENCAO_LEASE_OWNER = "retencao.lease_owner";
    public static final String RETENCAO_LEASE_UNTIL = "retencao.lease_until";
    public static final String RETENCAO_EXECUTADA_EM = "retencao.executada_em";
    public static final String RETENCAO_ORIGEM = "retencao_origem";

    public LinkCase {
        documentos = documentos == null ? Map.of() : Map.copyOf(documentos);
        arquivosTotal = Objects.requireNonNullElse(arquivosTotal, 0);
    }

    /** The stored path of one per-type counter, e.g. {@code documentos.RESPONSAVEL.bytes}. */
    public static String documentosField(DocumentoTipo tipo, String field) {
        return DOCUMENTOS + "." + tipo.name() + "." + field;
    }
}
