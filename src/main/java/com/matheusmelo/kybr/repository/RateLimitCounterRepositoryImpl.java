package com.matheusmelo.kybr.repository;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.matheusmelo.kybr.model.RateLimitCounter;
import java.time.Instant;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The atomic upsert-increment. A custom fragment is justified here by the layer rules: a derived
 * query can't express {@code $inc} + {@code $setOnInsert} with upsert in one round trip.
 */
class RateLimitCounterRepositoryImpl implements RateLimitCounterRepositoryCustom {

    private static final FindAndModifyOptions UPSERT_RETURN_NEW = FindAndModifyOptions.options()
            .upsert(true)
            .returnNew(true);

    private final MongoTemplate mongoTemplate;

    RateLimitCounterRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public long incrementAndGet(String id, Instant expiresAt) {
        Query byId = Query.query(where("_id").is(id));
        Update increment = new Update().inc(RateLimitCounter.COUNT, 1).setOnInsert(RateLimitCounter.EXPIRES_AT, expiresAt);
        RateLimitCounter counter = mongoTemplate.findAndModify(byId, increment, UPSERT_RETURN_NEW, RateLimitCounter.class);
        if (counter == null) {
            throw new IllegalStateException("upsert returned no document");
        }
        return counter.count();
    }
}
