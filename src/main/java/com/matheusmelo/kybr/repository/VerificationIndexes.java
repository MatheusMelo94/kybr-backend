package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.model.link.Verification;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.stereotype.Component;

/** A case's verifications within its workspace. */
@Component
class VerificationIndexes implements IndexDefinitionProvider {

    @Override
    public String collection() {
        return Verification.COLLECTION;
    }

    @Override
    public List<IndexDefinition> indexes() {
        return List.of(new Index().on(Verification.WORKSPACE_ID, Sort.Direction.ASC)
                .on(Verification.CASE_ID, Sort.Direction.ASC).named("workspace_case"));
    }
}
