package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.model.IdempotencyRecord;
import java.time.Duration;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.stereotype.Component;

/**
 * TTL on {@code expiresAt}. Housekeeping only: expiry is decided by the app clock
 * ({@code deleteIfExpired}), never by when the TTL monitor runs.
 */
@Component
class IdempotencyRecordIndexes implements IndexDefinitionProvider {

    @Override
    public String collection() {
        return IdempotencyRecord.COLLECTION;
    }

    @Override
    public List<IndexDefinition> indexes() {
        return List.of(new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO).named("expires_at_ttl"));
    }
}
