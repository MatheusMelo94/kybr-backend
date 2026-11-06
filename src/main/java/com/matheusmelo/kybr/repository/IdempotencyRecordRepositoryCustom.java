package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.IdempotencyRecord;
import java.time.Instant;

/**
 * The idempotency state machine's atomic, fenced operations, expiry decided by the app clock. Each
 * returns whether it matched a document; a {@code false} from a fenced write means the lease was
 * lost.
 */
public interface IdempotencyRecordRepositoryCustom {

    /** Inserts {@code record}; false if a record with its {@code _id} already exists. */
    boolean insertIfAbsent(IdempotencyRecord record);

    /**
     * Takes over an {@code IN_PROGRESS} record whose lease ended before {@code now}: sets
     * {@code leaseOwner} and {@code leaseUntil}. False when the lease is still held or the record
     * is not in progress.
     */
    boolean takeOverIfLeaseExpired(String id, String newOwner, Instant now, Instant leaseUntil);

    /**
     * Fenced completion: stores status, content type and the (already encrypted) body, matching
     * only {@code _id}, {@code state=IN_PROGRESS} and {@code leaseOwner=owner}.
     */
    boolean complete(String id, String owner, int status, String contentType, byte[] body);

    /** Fenced delete: matches only {@code _id}, {@code state=IN_PROGRESS} and {@code leaseOwner=owner}. */
    boolean delete(String id, String owner);

    /**
     * Deletes the record only if {@code expiresAt ≤ now} (the app clock decides expiry, never
     * Mongo's TTL monitor).
     */
    boolean deleteIfExpired(String id, Instant now);
}
