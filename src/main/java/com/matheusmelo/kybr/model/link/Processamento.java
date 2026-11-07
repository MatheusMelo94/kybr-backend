package com.matheusmelo.kybr.model.link;

import java.time.Instant;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A case's background verification state. Workers claim a case with one atomic update that sets
 * {@code EM_ANDAMENTO}, a fresh {@code leaseOwner} and {@code leaseUntil}; every later write is
 * fenced on {@code leaseOwner}.
 *
 * @param tentativas provider attempts so far
 * @param proximaTentativa when a {@code PENDENTE} case may next be claimed
 * @param motivoRevisao set with {@code REVISAO_MANUAL}
 */
public record Processamento(
        @Field("estado") EstadoProcessamento estado,
        @Field("tentativas") int tentativas,
        @Field("proxima_tentativa") Instant proximaTentativa,
        @Field("lease_owner") String leaseOwner,
        @Field("lease_until") Instant leaseUntil,
        @Field("motivo_revisao") MotivoRevisao motivoRevisao) {

    /** A new case's state: claimable now, never attempted. */
    public static Processamento pendente(Instant now) {
        return new Processamento(EstadoProcessamento.PENDENTE, 0, now, null, null, null);
    }
}
