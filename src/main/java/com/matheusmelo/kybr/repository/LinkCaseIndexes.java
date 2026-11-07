package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.model.link.LinkCase;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.stereotype.Component;

/**
 * Cases: one per token hash (the read path), claimable work by state and next attempt (the
 * workers' claim), and a workspace's cases per CNPJ over time.
 */
@Component
class LinkCaseIndexes implements IndexDefinitionProvider {

    @Override
    public String collection() {
        return LinkCase.COLLECTION;
    }

    @Override
    public List<IndexDefinition> indexes() {
        return List.of(
                new Index().on(LinkCase.TOKEN_HASH, Sort.Direction.ASC).unique().named("token_hash_unique"),
                new Index().on(LinkCase.PROCESSAMENTO_ESTADO, Sort.Direction.ASC)
                        .on(LinkCase.PROCESSAMENTO_PROXIMA_TENTATIVA, Sort.Direction.ASC)
                        .named("processamento_claim"),
                new Index().on(LinkCase.WORKSPACE_ID, Sort.Direction.ASC).on(LinkCase.CNPJ, Sort.Direction.ASC)
                        .on(LinkCase.CRIADO_EM, Sort.Direction.ASC).named("workspace_cnpj_criado"));
    }
}
