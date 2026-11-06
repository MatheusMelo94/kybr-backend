package com.matheusmelo.kybr.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.stereotype.Component;

/**
 * Creates every {@link IndexDefinitionProvider}'s indexes at startup, before the app reports
 * ready. Creating an index that already exists with the same spec is a no-op, so restarts are
 * safe; a conflicting spec fails startup rather than running without the index.
 */
@Component
public class IndexBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexBootstrap.class);

    private final MongoTemplate mongoTemplate;
    private final List<IndexDefinitionProvider> providers;

    public IndexBootstrap(MongoTemplate mongoTemplate, List<IndexDefinitionProvider> providers) {
        this.mongoTemplate = mongoTemplate;
        this.providers = providers;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (IndexDefinitionProvider provider : providers) {
            for (IndexDefinition index : provider.indexes()) {
                String name = mongoTemplate.indexOps(provider.collection()).createIndex(index);
                log.info("event=index_ensured collection={} index={}", provider.collection(), name);
            }
        }
    }
}
