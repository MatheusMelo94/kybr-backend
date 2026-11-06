package com.matheusmelo.kybr.config;

import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import java.util.concurrent.TimeUnit;
import org.bson.UuidRepresentation;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * MongoDB client settings and transactions. Always a replica set, so
 * multi-document transactions work in every environment.
 */
@Configuration(proxyBeanMethods = false)
public class MongoConfig {

    /**
     * Runs after Boot's standard customizer (which applies {@code MONGODB_URI}), so these values
     * hold even if the URI asks for weaker ones. The 5 s socket timeout keeps writes inside the
     * frontend's 8 s budget.
     */
    @Bean
    MongoClientSettingsBuilderCustomizer kybrMongoClientSettings() {
        return new KybrClientSettings();
    }

    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }

    private static final class KybrClientSettings implements MongoClientSettingsBuilderCustomizer, Ordered {

        @Override
        public void customize(MongoClientSettings.Builder builder) {
            builder.applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(3, TimeUnit.SECONDS))
                    .applyToSocketSettings(socket -> socket
                            .connectTimeout(3, TimeUnit.SECONDS)
                            .readTimeout(5, TimeUnit.SECONDS))
                    .retryWrites(true)
                    .writeConcern(WriteConcern.MAJORITY)
                    .uuidRepresentation(UuidRepresentation.STANDARD);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
