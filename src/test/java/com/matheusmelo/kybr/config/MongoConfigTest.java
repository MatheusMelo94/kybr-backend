package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import java.util.concurrent.TimeUnit;
import org.bson.UuidRepresentation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.core.Ordered;

/** The client settings hold even when the URI asks for weaker ones. */
class MongoConfigTest {

    private final MongoClientSettingsBuilderCustomizer customizer = new MongoConfig().kybrMongoClientSettings();

    @Test
    void overridesWeakerSettingsFromTheUri() {
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .uuidRepresentation(UuidRepresentation.JAVA_LEGACY)
                .applyConnectionString(new ConnectionString("mongodb://127.0.0.1:1/kybr?replicaSet=rs0"
                        + "&w=1&retryWrites=false&connectTimeoutMS=60000&socketTimeoutMS=0"
                        + "&serverSelectionTimeoutMS=30000"));

        customizer.customize(builder);
        MongoClientSettings settings = builder.build();

        assertThat(settings.getWriteConcern()).isEqualTo(WriteConcern.MAJORITY);
        assertThat(settings.getRetryWrites()).isTrue();
        assertThat(settings.getUuidRepresentation()).isEqualTo(UuidRepresentation.STANDARD);
        assertThat(settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(3000);
        assertThat(settings.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS)).isEqualTo(3000);
        assertThat(settings.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(5000);
    }

    @Test
    void runsAfterBootsStandardCustomizer() {
        assertThat(customizer).isInstanceOf(Ordered.class);
        assertThat(((Ordered) customizer).getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
