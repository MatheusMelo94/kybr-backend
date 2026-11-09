package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.model.link.CaseEvent;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.stereotype.Component;

/** A case's timeline, in order. */
@Component
class CaseEventIndexes implements IndexDefinitionProvider {

    @Override
    public String collection() {
        return CaseEvent.COLLECTION;
    }

    @Override
    public List<IndexDefinition> indexes() {
        return List.of(new Index().on(CaseEvent.CASE_ID, Sort.Direction.ASC)
                .on(CaseEvent.OCORRIDO_EM, Sort.Direction.ASC).named("case_ocorrido"));
    }
}
