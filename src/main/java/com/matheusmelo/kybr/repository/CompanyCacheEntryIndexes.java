package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.model.link.CompanyCacheEntry;
import java.time.Duration;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.stereotype.Component;

/** TTL on {@code expira_em}. Housekeeping only: freshness is decided by the app clock. */
@Component
class CompanyCacheEntryIndexes implements IndexDefinitionProvider {

    @Override
    public String collection() {
        return CompanyCacheEntry.COLLECTION;
    }

    @Override
    public List<IndexDefinition> indexes() {
        return List.of(new Index().on(CompanyCacheEntry.EXPIRA_EM, Sort.Direction.ASC).expire(Duration.ZERO)
                .named("expira_em_ttl"));
    }
}
