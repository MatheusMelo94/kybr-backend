package com.matheusmelo.kybr.model.link;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A durable notification waiting for a sender. At most one unprocessed event per (case, tipo):
 * a unique index over unprocessed events coalesces repeated changes. An unprocessed event expires
 * at {@code expiraEm}.
 *
 * @param payloadCifrado AEAD-encrypted under the personal-data keyset (AAD = event id)
 * @param processadoEm when a sender handled it; null while unprocessed
 * @param pendente true exactly while {@code processadoEm} is null. Derived, and stored so the
 *     coalescing index can filter on an equality every supported server version accepts; a
 *     direct update that sets {@code processado_em} must also set {@code pendente} to false
 */
@Document(OutboxEvent.COLLECTION)
public record OutboxEvent(
        @Id UUID id,
        @Field("tipo") TipoOutbox tipo,
        @Field("case_id") UUID caseId,
        @Field("payload_cifrado") byte[] payloadCifrado,
        @Field("criado_em") Instant criadoEm,
        @Field("processado_em") Instant processadoEm,
        @Field("pendente") boolean pendente,
        @Field("expira_em") Instant expiraEm) {

    public static final String COLLECTION = "outbox_events";
    public static final String TIPO = "tipo";
    public static final String CASE_ID = "case_id";
    public static final String CRIADO_EM = "criado_em";
    public static final String PROCESSADO_EM = "processado_em";
    public static final String PENDENTE = "pendente";
    public static final String EXPIRA_EM = "expira_em";

    public OutboxEvent {
        pendente = processadoEm == null;
    }

    /** A new, unprocessed event. */
    public OutboxEvent(UUID id, TipoOutbox tipo, UUID caseId, byte[] payloadCifrado, Instant criadoEm,
            Instant expiraEm) {
        this(id, tipo, caseId, payloadCifrado, criadoEm, null, true, expiraEm);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutboxEvent that && Objects.equals(id, that.id) && tipo == that.tipo
                && Objects.equals(caseId, that.caseId) && Arrays.equals(payloadCifrado, that.payloadCifrado)
                && Objects.equals(criadoEm, that.criadoEm) && Objects.equals(processadoEm, that.processadoEm)
                && pendente == that.pendente && Objects.equals(expiraEm, that.expiraEm);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tipo, caseId, Arrays.hashCode(payloadCifrado), criadoEm, processadoEm, pendente,
                expiraEm);
    }

    @Override
    public String toString() {
        return "OutboxEvent[id=" + id + ", tipo=" + tipo + ", caseId=" + caseId + ", pendente=" + pendente + "]";
    }
}
