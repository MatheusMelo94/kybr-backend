package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.model.link.AuditEvent;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.stereotype.Component;

/** A workspace's audit trail in time order. */
@Component
class AuditEventIndexes implements IndexDefinitionProvider {

    @Override
    public String collection() {
        return AuditEvent.COLLECTION;
    }

    @Override
    public List<IndexDefinition> indexes() {
        return List.of(new Index().on(AuditEvent.WORKSPACE_ID, Sort.Direction.ASC)
                .on(AuditEvent.OCORRIDO_EM, Sort.Direction.ASC).named("workspace_ocorrido"));
    }
}
