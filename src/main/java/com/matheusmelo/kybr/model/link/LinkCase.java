package com.matheusmelo.kybr.model.link;

import com.matheusmelo.kybr.model.workspace.Politica;
import java.time.Instant;
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
 * @param retencaoAte when the case's personal data is due for deletion; set when it's decided
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
        @Field("retencao_ate") Instant retencaoAte) {

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
}
