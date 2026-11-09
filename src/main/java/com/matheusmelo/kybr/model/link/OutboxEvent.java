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
 * @param processadoEm when a sender handled it (sent, or dropped by a cap); null while unprocessed
 * @param pendente true exactly while both {@code processadoEm} and {@code falhouEm} are null.
 *     Derived, and stored so the coalescing index and the dispatcher's claim can filter on an
 *     equality every supported server version accepts; a direct update that sets
 *     {@code processado_em} or {@code falhou_em} must also set {@code pendente} to false
 * @param tentativas delivery attempts so far
 * @param proximaTentativa when it's next due; null on events stored before delivery existed
 * @param leaseOwner the dispatcher run holding the claim; null when unclaimed
 * @param leaseUntil when another run may take the claim over
 * @param falhouEm when delivery gave up (a permanent failure, or the retries ran out)
 * @param resultadoEnvio how the e-mail ended, once processed
 *
 * <p>Events stored before delivery existed lack the last six fields: they read as zero attempts
 * and nulls.
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
        @Field("expira_em") Instant expiraEm,
        @Field("tentativas") Integer tentativas,
        @Field("proxima_tentativa") Instant proximaTentativa,
        @Field("lease_owner") String leaseOwner,
        @Field("lease_until") Instant leaseUntil,
        @Field("falhou_em") Instant falhouEm,
        @Field("resultado_envio") ResultadoEnvio resultadoEnvio) {

    public static final String COLLECTION = "outbox_events";
    public static final String TIPO = "tipo";
    public static final String CASE_ID = "case_id";
    public static final String CRIADO_EM = "criado_em";
    public static final String PROCESSADO_EM = "processado_em";
    public static final String PENDENTE = "pendente";
    public static final String EXPIRA_EM = "expira_em";
    public static final String TENTATIVAS = "tentativas";
    public static final String PROXIMA_TENTATIVA = "proxima_tentativa";
    public static final String LEASE_OWNER = "lease_owner";
    public static final String LEASE_UNTIL = "lease_until";
    public static final String FALHOU_EM = "falhou_em";
    public static final String RESULTADO_ENVIO = "resultado_envio";

    public OutboxEvent {
        pendente = processadoEm == null && falhouEm == null;
        tentativas = Objects.requireNonNullElse(tentativas, 0);
    }

    /** A new, unprocessed event, due at once. */
    public OutboxEvent(UUID id, TipoOutbox tipo, UUID caseId, byte[] payloadCifrado, Instant criadoEm,
            Instant expiraEm) {
        this(id, tipo, caseId, payloadCifrado, criadoEm, null, true, expiraEm, 0, criadoEm, null, null, null, null);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutboxEvent that && Objects.equals(id, that.id) && tipo == that.tipo
                && Objects.equals(caseId, that.caseId) && Arrays.equals(payloadCifrado, that.payloadCifrado)
                && Objects.equals(criadoEm, that.criadoEm) && Objects.equals(processadoEm, that.processadoEm)
                && pendente == that.pendente && Objects.equals(expiraEm, that.expiraEm)
                && Objects.equals(tentativas, that.tentativas) && Objects.equals(proximaTentativa, that.proximaTentativa)
                && Objects.equals(leaseOwner, that.leaseOwner) && Objects.equals(leaseUntil, that.leaseUntil)
                && Objects.equals(falhouEm, that.falhouEm) && resultadoEnvio == that.resultadoEnvio;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tipo, caseId, Arrays.hashCode(payloadCifrado), criadoEm, processadoEm, pendente,
                expiraEm, tentativas, proximaTentativa, leaseOwner, leaseUntil, falhouEm, resultadoEnvio);
    }

    @Override
    public String toString() {
        return "OutboxEvent[id=" + id + ", tipo=" + tipo + ", caseId=" + caseId + ", pendente=" + pendente
                + ", tentativas=" + tentativas + "]";
    }
}
