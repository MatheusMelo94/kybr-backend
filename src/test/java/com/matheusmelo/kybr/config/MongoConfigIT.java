package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bson.BsonBinarySubType;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class MongoConfigIT extends MongoIntegrationTest {

    private static final String COLLECTION = "mongo_config_it";

    @Autowired
    private MongoClientSettings settingsBean;

    @Autowired
    private ObjectProvider<MongoClientSettingsBuilderCustomizer> customizers;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void clean() {
        mongoTemplate.dropCollection(COLLECTION);
        mongoTemplate.createCollection(COLLECTION);
    }

    @Test
    void clientSettingsMatchThePlan() {
        // The settings the client is built from: the bean, then every customizer in order
        // (Boot's standard one applies the URI; ours runs last).
        MongoClientSettings.Builder builder = MongoClientSettings.builder(settingsBean);
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        MongoClientSettings settings = builder.build();

        assertThat(settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(3000);
        assertThat(settings.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS)).isEqualTo(3000);
        assertThat(settings.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(5000);
        assertThat(settings.getRetryWrites()).isTrue();
        assertThat(settings.getWriteConcern()).isEqualTo(WriteConcern.MAJORITY);
        assertThat(settings.getUuidRepresentation()).isEqualTo(UuidRepresentation.STANDARD);
    }

    @Test
    void theLiveClientWritesWithMajorityAndStandardUuids() {
        assertThat(mongoTemplate.getDb().getWriteConcern()).isEqualTo(WriteConcern.MAJORITY);

        UUID id = UUID.randomUUID();
        mongoTemplate.insert(new Document("_id", "uuid-check").append("value", id), COLLECTION);

        BsonDocument raw = mongoTemplate.getDb().getCollection(COLLECTION, BsonDocument.class)
                .find(new Document("_id", "uuid-check")).first();
        assertThat(raw.get("value").isBinary()).isTrue();
        assertThat(raw.getBinary("value").getType()).isEqualTo(BsonBinarySubType.UUID_STANDARD.getValue());
    }

    @Test
    void usesTheMongoTransactionManager() {
        assertThat(transactionManager).isInstanceOf(MongoTransactionManager.class);
    }

    @Test
    void aMultiDocumentTransactionCommits() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            mongoTemplate.insert(new Document("_id", "a"), COLLECTION);
            mongoTemplate.insert(new Document("_id", "b"), COLLECTION);
        });

        assertThat(mongoTemplate.getCollection(COLLECTION).countDocuments()).isEqualTo(2);
    }

    @Test
    void aMultiDocumentTransactionRollsBack() {
        assertThatIllegalStateException().isThrownBy(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    mongoTemplate.insert(new Document("_id", "a"), COLLECTION);
                    mongoTemplate.insert(new Document("_id", "b"), COLLECTION);
                    throw new IllegalStateException("synthetic failure after both writes");
                }));

        assertThat(mongoTemplate.getCollection(COLLECTION).countDocuments()).isZero();
    }
}
