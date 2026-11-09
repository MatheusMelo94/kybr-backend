package com.matheusmelo.kybr.model.link;

import java.time.Instant;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A case's retention run: claimed with a lease like the verification work, then concluded.
 *
 * @param leaseOwner the run holding the claim; null when unclaimed
 * @param leaseUntil when another run may take the claim over
 * @param executadaEm when the deletion completed
 */
public record Retencao(
        @Field("estado") EstadoRetencao estado,
        @Field("lease_owner") String leaseOwner,
        @Field("lease_until") Instant leaseUntil,
        @Field("executada_em") Instant executadaEm) {
}
