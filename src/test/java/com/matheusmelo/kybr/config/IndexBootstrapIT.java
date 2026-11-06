package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.matheusmelo.kybr.support.MongoIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;

class IndexBootstrapIT extends MongoIntegrationTest {

    /** Unique per run, so "created on startup" can't be satisfied by a leftover index. */
    private static final String COLLECTION = "index_bootstrap_it_" + UUID.randomUUID().toString().replace("-", "");
    private static final String INDEX = "expires_at_ttl";

    @TestConfiguration
    static class TtlProvider {
        @Bean
        IndexDefinitionProvider testTtlIndex() {
            return new IndexDefinitionProvider() {
                @Override
                public String collection() {
                    return COLLECTION;
                }

                @Override
                public List<IndexDefinition> indexes() {
                    return List.of(new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO).named(INDEX));
                }
            };
        }
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private IndexBootstrap indexBootstrap;

    private static MongoTemplate cleanup;

    @Test
    void createsTheProvidersTtlIndexOnStartup() {
        cleanup = mongoTemplate;

        IndexInfo index = ttlIndex();
        assertThat(index.getExpireAfter()).contains(Duration.ZERO);
        assertThat(index.getIndexFields()).singleElement()
                .satisfies(field -> assertThat(field.getKey()).isEqualTo("expiresAt"));
    }

    @Test
    void aSecondStartupIsANoOp() {
        cleanup = mongoTemplate;
        List<IndexInfo> before = mongoTemplate.indexOps(COLLECTION).getIndexInfo();

        assertThatNoException().isThrownBy(() -> indexBootstrap.run(new DefaultApplicationArguments()));

        assertThat(mongoTemplate.indexOps(COLLECTION).getIndexInfo()).hasSameSizeAs(before);
        assertThat(ttlIndex().getExpireAfter()).contains(Duration.ZERO);
    }

    @AfterAll
    static void dropCollection() {
        if (cleanup != null) {
            cleanup.dropCollection(COLLECTION);
        }
    }

    private IndexInfo ttlIndex() {
        return mongoTemplate.indexOps(COLLECTION).getIndexInfo().stream()
                .filter(info -> INDEX.equals(info.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TTL index not created"));
    }
}
