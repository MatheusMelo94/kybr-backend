package com.matheusmelo.kybr.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.RateLimitCounter;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

/** The counter store on the replica set. */
class RateLimitCounterRepositoryIT extends MongoIntegrationTest {

    private static final String POLICY = "test-policy";
    private static final long WINDOW = 1_790_000_000L;
    private static final Instant EXPIRES = Instant.ofEpochSecond(WINDOW + 60 + 60);
    private static final AtomicLong SUBJECTS = new AtomicLong();

    @Autowired
    private RateLimitCounterRepository repository;

    @Autowired
    private KeyHasher keyHasher;

    @Autowired
    private MongoTemplate mongoTemplate;

    private String subjectHmac;

    @BeforeEach
    void freshSubject() {
        subjectHmac = keyHasher.hmac("ip:198.51.100." + SUBJECTS.incrementAndGet());
    }

    @Test
    void fiftyConcurrentIncrementsOnOneKeyEndAtFifty() throws Exception {
        String id = RateLimitCounter.idFor(POLICY, subjectHmac, WINDOW);
        List<Future<Long>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                results.add(pool.submit(() -> repository.incrementAndGet(id, EXPIRES)));
            }
        }

        List<Long> counts = new ArrayList<>();
        for (Future<Long> result : results) {
            counts.add(result.get());
        }
        assertThat(counts).containsExactlyInAnyOrderElementsOf(range(1, 50));
        assertThat(repository.findById(id)).get().satisfies(counter -> {
            assertThat(counter.count()).isEqualTo(50);
            assertThat(counter.expiresAt()).isEqualTo(EXPIRES);
        });
    }

    @Test
    void distinctWindowsAreDistinctDocuments() {
        String first = RateLimitCounter.idFor(POLICY, subjectHmac, WINDOW);
        String next = RateLimitCounter.idFor(POLICY, subjectHmac, WINDOW + 60);

        assertThat(repository.incrementAndGet(first, EXPIRES)).isEqualTo(1);
        assertThat(repository.incrementAndGet(first, EXPIRES)).isEqualTo(2);
        assertThat(repository.incrementAndGet(next, EXPIRES.plusSeconds(60))).isEqualTo(1);

        assertThat(repository.findById(first)).get().extracting(RateLimitCounter::count).isEqualTo(2L);
        assertThat(repository.findById(next)).get().extracting(RateLimitCounter::count).isEqualTo(1L);
    }

    @Test
    void theStoredIdHoldsOnlyThePolicyTheHmacAndTheWindow() {
        String raw = "ip:198.51.100.200";
        String id = RateLimitCounter.idFor(POLICY, keyHasher.hmac(raw), WINDOW);

        repository.incrementAndGet(id, EXPIRES);

        Document stored = mongoTemplate.getCollection(RateLimitCounter.COLLECTION).find(new Document("_id", id)).first();
        assertThat(stored).isNotNull();
        assertThat(stored.getString("_id")).isEqualTo(POLICY + ":" + keyHasher.hmac(raw) + ":" + WINDOW)
                .doesNotContain("198.51.100.200");
        assertThat(stored.keySet()).containsExactlyInAnyOrder("_id", "count", "expiresAt");
        assertThat(stored.toJson()).doesNotContain("198.51.100.200");
    }

    @Test
    void theTtlIndexOnExpiresAtExists() {
        IndexInfo ttl = mongoTemplate.indexOps(RateLimitCounter.COLLECTION).getIndexInfo().stream()
                .filter(info -> info.getExpireAfter().isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no TTL index on " + RateLimitCounter.COLLECTION));

        assertThat(ttl.getExpireAfter()).contains(Duration.ZERO);
        assertThat(ttl.getIndexFields()).singleElement()
                .satisfies(field -> assertThat(field.getKey()).isEqualTo("expiresAt"));
    }

    private static List<Long> range(long from, long to) {
        List<Long> values = new ArrayList<>();
        for (long v = from; v <= to; v++) {
            values.add(v);
        }
        return values;
    }
}
