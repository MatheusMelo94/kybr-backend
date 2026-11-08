package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.model.document.Document;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.schema.JsonSchemaObject;
import org.springframework.stereotype.Component;

/**
 * Documents: a case's pages in order, lookup by stored hash, one counting record per original file
 * per case and type (so concurrent identical retries can't both commit; a refused file no longer
 * counts), one record per final and per quarantine object key (the orphan check), and the scan
 * job's due rows.
 */
@Component
class DocumentIndexes implements IndexDefinitionProvider {

    @Override
    public String collection() {
        return Document.COLLECTION;
    }

    @Override
    public List<IndexDefinition> indexes() {
        return List.of(
                new Index().on(Document.CASE_ID, Sort.Direction.ASC).on(Document.TIPO, Sort.Direction.ASC)
                        .on(Document.PAGINA, Sort.Direction.ASC).named("case_tipo_pagina"),
                new Index().on(Document.CASE_ID, Sort.Direction.ASC).on(Document.SHA256, Sort.Direction.ASC)
                        .named("case_sha256"),
                new Index().on(Document.CASE_ID, Sort.Direction.ASC).on(Document.TIPO, Sort.Direction.ASC)
                        .on(Document.SHA256_ORIGINAL, Sort.Direction.ASC).unique()
                        .partial(PartialIndexFilter.of(Criteria.where(Document.ATIVO).is(true)))
                        .named("case_tipo_sha256_original_ativo_unique"),
                new Index().on(Document.STORAGE_KEY, Sort.Direction.ASC).unique()
                        .partial(PartialIndexFilter.of(Criteria.where(Document.STORAGE_KEY).type(JsonSchemaObject.Type.STRING)))
                        .named("s3_key_stored_unique"),
                new Index().on(Document.QUARENTENA_KEY, Sort.Direction.ASC).unique()
                        .partial(PartialIndexFilter.of(Criteria.where(Document.QUARENTENA_KEY).type(JsonSchemaObject.Type.STRING)))
                        .named("quarentena_key_unique"),
                new Index().on(Document.ESTADO, Sort.Direction.ASC).on(Document.PROXIMA_TENTATIVA, Sort.Direction.ASC)
                        .named("estado_proxima_tentativa"));
    }
}
