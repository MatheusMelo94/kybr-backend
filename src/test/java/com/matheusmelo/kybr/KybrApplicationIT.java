package com.matheusmelo.kybr;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.MongoIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;

class KybrApplicationIT extends MongoIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private Environment environment;

    @Test
    void contextBootsAgainstAReplicaSet() {
        assertThat(context.getBean(KybrApplication.class)).isNotNull();

        Document hello = mongoTemplate.executeCommand(new Document("hello", 1));
        assertThat(hello.getString("setName")).as("MongoDB must be a replica set").isNotBlank();
    }

    @Test
    void virtualThreadsAreEnabled() {
        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean.class)).isTrue();
    }
}
