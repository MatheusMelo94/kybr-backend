package com.matheusmelo.kybr.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Base class for integration tests that need MongoDB.
 *
 * <p>MongoDB is always a <b>single-node replica set</b>, so multi-document transactions work.
 * The source depends on the environment:
 *
 * <ul>
 *   <li><b>Default (CI, any machine with Docker):</b> a Testcontainers {@code mongo:8.0}
 *       container, which Testcontainers starts as a single-node replica set. It is started once
 *       per JVM and shared by every subclass.
 *   <li><b>{@code KYBR_TEST_MONGO_URI} set:</b> that external MongoDB is used instead and no
 *       container is started. It must be a replica set, e.g.
 *       {@code KYBR_TEST_MONGO_URI='mongodb://127.0.0.1:27017/?replicaSet=rs0' ./mvnw verify}.
 *       Use this where Docker isn't available. Tests use the {@value #DATABASE} database on it
 *       (or {@code KYBR_TEST_MONGO_DB} when set, so parallel runs on one server don't collide),
 *       and may drop collections there.
 * </ul>
 *
 * <p>The application's secrets are generated in memory by {@link TestSecrets} and supplied by
 * {@link TestSecretsContextCustomizerFactory}, which also points the console redactor at them; no
 * test key material is committed.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class MongoIntegrationTest {

    public static final String EXTERNAL_URI_ENV = "KYBR_TEST_MONGO_URI";
    public static final String EXTERNAL_DB_ENV = "KYBR_TEST_MONGO_DB";
    public static final String DATABASE = "kybr-test";

    private static final String IMAGE = "mongo:8.0";

    private static MongoDBContainer container;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", MongoIntegrationTest::mongoUri);
        registry.add("spring.mongodb.database", MongoIntegrationTest::database);
    }

    /** The test database: {@code KYBR_TEST_MONGO_DB} when set, else {@value #DATABASE}. */
    public static String database() {
        String override = System.getenv(EXTERNAL_DB_ENV);
        return override != null && !override.isBlank() ? override : DATABASE;
    }

    public static synchronized String mongoUri() {
        String external = System.getenv(EXTERNAL_URI_ENV);
        if (external != null && !external.isBlank()) {
            return external;
        }
        if (container == null) {
            container = new MongoDBContainer(IMAGE);
            container.start();
        }
        return container.getReplicaSetUrl(database());
    }
}
