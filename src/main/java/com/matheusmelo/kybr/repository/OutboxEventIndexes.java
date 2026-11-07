package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import java.time.Duration;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * The sender's queue (unprocessed first, oldest first), expiry, and at most one unprocessed event
 * per (case, tipo), which coalesces repeated changes.
 */
@Component
class OutboxEventIndexes implements IndexDefinitionProvider {

    @Override
    public String collection() {
        return OutboxEvent.COLLECTION;
    }

    @Override
    public List<IndexDefinition> indexes() {
        return List.of(
                new Index().on(OutboxEvent.PROCESSADO_EM, Sort.Direction.ASC)
                        .on(OutboxEvent.CRIADO_EM, Sort.Direction.ASC).named("processado_criado"),
                new Index().on(OutboxEvent.EXPIRA_EM, Sort.Direction.ASC).expire(Duration.ZERO)
                        .named("expira_em_ttl"),
                new Index().on(OutboxEvent.CASE_ID, Sort.Direction.ASC).on(OutboxEvent.TIPO, Sort.Direction.ASC)
                        .unique().partial(PartialIndexFilter.of(Criteria.where(OutboxEvent.PENDENTE).is(true)))
                        .named("case_tipo_pendente_unique"));
    }
}
