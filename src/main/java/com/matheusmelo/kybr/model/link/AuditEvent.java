package com.matheusmelo.kybr.model.link;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One append-only audit record. Nothing updates or deletes audit events.
 *
 * @param clientIp the caller's IP when known
 * @param ipDesconhecido true when the client IP wasn't known
 * @param sessionHash the panel session's hash; null until panel sessions exist
 * @param tokenSlot which admin token an operator used; null for other actors
 * @param resumo masked details only
 */
@Document(AuditEvent.COLLECTION)
public record AuditEvent(
        @Id UUID id,
        @Field("workspace_id") UUID workspaceId,
        @Field("tipo") TipoAuditoria tipo,
        @Field("ator") Ator ator,
        @Field("case_id") UUID caseId,
        @Field("ocorrido_em") Instant ocorridoEm,
        @Field("client_ip") String clientIp,
        @Field("ip_desconhecido") boolean ipDesconhecido,
        @Field("session_hash") String sessionHash,
        @Field("token_slot") TokenSlot tokenSlot,
        @Field("resumo") ResumoAuditoria resumo) {

    public static final String COLLECTION = "audit_events";
    public static final String WORKSPACE_ID = "workspace_id";
    public static final String OCORRIDO_EM = "ocorrido_em";
}
