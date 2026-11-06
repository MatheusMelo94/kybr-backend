package com.matheusmelo.kybr.repository;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.matheusmelo.kybr.model.IdempotencyRecord;
import com.matheusmelo.kybr.model.IdempotencyRecord.State;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Conditional single-document writes for the idempotency state machine. A custom fragment is
 * justified by the layer rules: each operation must be one atomic, conditional write so that two
 * attempts can never both win. Inside a Spring-managed Mongo transaction these writes join it
 * ({@code completeInTransaction}).
 */
class IdempotencyRecordRepositoryImpl implements IdempotencyRecordRepositoryCustom {

    private static final String ID = "_id";
    private static final String STATE = "state";
    private static final String LEASE_OWNER = "leaseOwner";
    private static final String LEASE_UNTIL = "leaseUntil";
    private static final String EXPIRES_AT = "expiresAt";

    private final MongoTemplate mongoTemplate;

    IdempotencyRecordRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public boolean insertIfAbsent(IdempotencyRecord record) {
        try {
            mongoTemplate.insert(record);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean takeOverIfLeaseExpired(String id, String newOwner, Instant now, Instant leaseUntil) {
        Query expiredLease = Query.query(where(ID).is(id).and(STATE).is(State.IN_PROGRESS).and(LEASE_UNTIL).lt(now));
        Update takeOver = new Update().set(LEASE_OWNER, newOwner).set(LEASE_UNTIL, leaseUntil);
        return mongoTemplate.updateFirst(expiredLease, takeOver, IdempotencyRecord.class).getModifiedCount() == 1;
    }

    @Override
    public boolean complete(String id, String owner, int status, String contentType, byte[] body) {
        Update completion = new Update()
                .set(STATE, State.COMPLETED)
                .set("status", status)
                .set("contentType", contentType)
                .set("body", body);
        return mongoTemplate.updateFirst(fenced(id, owner), completion, IdempotencyRecord.class).getModifiedCount() == 1;
    }

    @Override
    public boolean delete(String id, String owner) {
        return mongoTemplate.remove(fenced(id, owner), IdempotencyRecord.class).getDeletedCount() == 1;
    }

    @Override
    public boolean deleteIfExpired(String id, Instant now) {
        Query expired = Query.query(where(ID).is(id).and(EXPIRES_AT).lte(now));
        return mongoTemplate.remove(expired, IdempotencyRecord.class).getDeletedCount() == 1;
    }

    /** {@code _id}, {@code state=IN_PROGRESS} and {@code leaseOwner}: the lease-owner fence. */
    private static Query fenced(String id, String owner) {
        return Query.query(where(ID).is(id).and(STATE).is(State.IN_PROGRESS).and(LEASE_OWNER).is(owner));
    }
}
