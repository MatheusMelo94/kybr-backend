package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.model.document.DocumentRequest;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.stereotype.Component;

/** Document requests: one per token hash (the upload path), and a case's requests (closing them). */
@Component
class DocumentRequestIndexes implements IndexDefinitionProvider {

    @Override
    public String collection() {
        return DocumentRequest.COLLECTION;
    }

    @Override
    public List<IndexDefinition> indexes() {
        return List.of(
                new Index().on(DocumentRequest.TOKEN_HASH, Sort.Direction.ASC).unique().named("token_hash_unique"),
                new Index().on(DocumentRequest.CASE_ID, Sort.Direction.ASC).named("case_id"));
    }
}
