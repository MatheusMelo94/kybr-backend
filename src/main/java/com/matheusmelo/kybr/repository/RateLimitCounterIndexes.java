package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.model.RateLimitCounter;
import java.time.Duration;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.stereotype.Component;

/** TTL on {@code expiresAt} (window end + 1 min, set by the limiter). */
@Component
class RateLimitCounterIndexes implements IndexDefinitionProvider {

    @Override
    public String collection() {
        return RateLimitCounter.COLLECTION;
    }

    @Override
    public List<IndexDefinition> indexes() {
        return List.of(new Index().on(RateLimitCounter.EXPIRES_AT, Sort.Direction.ASC)
                .expire(Duration.ZERO)
                .named("expires_at_ttl"));
    }
}
