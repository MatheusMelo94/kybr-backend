package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.model.link.Seller;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.stereotype.Component;

/** One seller per (workspace, CNPJ), so concurrent verifications upsert the same document. */
@Component
class SellerIndexes implements IndexDefinitionProvider {

    @Override
    public String collection() {
        return Seller.COLLECTION;
    }

    @Override
    public List<IndexDefinition> indexes() {
        return List.of(new Index().on(Seller.WORKSPACE_ID, Sort.Direction.ASC).on(Seller.CNPJ, Sort.Direction.ASC)
                .unique().named("workspace_cnpj_unique"));
    }
}
