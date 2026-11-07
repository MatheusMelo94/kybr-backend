package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.model.workspace.Workspace;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.stereotype.Component;

/** One workspace per slug: the slug is the link's public address. */
@Component
class WorkspaceIndexes implements IndexDefinitionProvider {

    @Override
    public String collection() {
        return Workspace.COLLECTION;
    }

    @Override
    public List<IndexDefinition> indexes() {
        return List.of(new Index().on(Workspace.SLUG, Sort.Direction.ASC).unique().named("slug_unique"));
    }
}
