package com.matheusmelo.kybr.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One idempotency record. {@code _id} is the HMAC of
 * {@code profile|binding|key}, never the raw key. {@code body} is Tink AEAD ciphertext
 * (AAD = {@code _id}) and is never logged. {@code leaseOwner} is the fencing token of the
 * attempt that holds the lease, so a stale attempt can never write.
 */
@Document(IdempotencyRecord.COLLECTION)
public record IdempotencyRecord(
        @Id String id,
        String profile,
        String requestHash,
        State state,
        String leaseOwner,
        Instant leaseUntil,
        Integer status,
        String contentType,
        byte[] body,
        Instant createdAt,
        Instant expiresAt) {

    public static final String COLLECTION = "idempotency_records";

    public enum State {
        IN_PROGRESS,
        COMPLETED
    }

    /** A fresh record for the attempt {@code leaseOwner}, holding the lease until {@code leaseUntil}. */
    public static IdempotencyRecord inProgress(String id, String profile, String requestHash, String leaseOwner,
            Instant now, Instant leaseUntil, Instant expiresAt) {
        return new IdempotencyRecord(id, profile, requestHash, State.IN_PROGRESS, leaseOwner, leaseUntil, null, null,
                null, now, expiresAt);
    }

    @Override
    public String toString() {
        return "IdempotencyRecord[profile=" + profile + ", state=" + state + ", status=" + status + "]";
    }
}
