package com.matheusmelo.kybr.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.IdempotencyRecord;
import com.matheusmelo.kybr.model.IdempotencyRecord.State;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

/** App-clock expiry and lease fencing: the record store on the replica set. */
class IdempotencyRecordRepositoryIT extends MongoIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Duration TTL = Duration.ofMinutes(10);

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void twoConcurrentInsertsOnOneIdLetExactlyOneWin() throws Exception {
        String id = newId();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String owner : List.of("owner-a", "owner-b")) {
                results.add(pool.submit(() -> {
                    start.await();
                    return repository.insertIfAbsent(inProgress(id, owner, NOW));
                }));
            }
            start.countDown();
        }

        assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
        assertThat(repository.findById(id)).get().extracting(IdempotencyRecord::state).isEqualTo(State.IN_PROGRESS);
    }

    @Test
    void takeoverSucceedsOnlyOnceTheLeaseHasExpiredAndSetsANewOwner() {
        String id = newId();
        repository.insertIfAbsent(inProgress(id, "owner-a", NOW));
        Instant leaseEnd = NOW.plus(LEASE);

        assertThat(repository.takeOverIfLeaseExpired(id, "owner-b", leaseEnd, leaseEnd.plus(LEASE))).isFalse();
        assertThat(repository.findById(id)).get().extracting(IdempotencyRecord::leaseOwner).isEqualTo("owner-a");

        Instant later = leaseEnd.plusMillis(1);
        assertThat(repository.takeOverIfLeaseExpired(id, "owner-b", later, later.plus(LEASE))).isTrue();
        assertThat(repository.findById(id)).get().satisfies(record -> {
            assertThat(record.leaseOwner()).isEqualTo("owner-b");
            assertThat(record.leaseUntil()).isEqualTo(later.plus(LEASE));
        });
    }

    @Test
    void completeStoresStatusContentTypeAndBodyBytes() {
        String id = newId();
        repository.insertIfAbsent(inProgress(id, "owner-a", NOW));
        byte[] body = {0x00, (byte) 0xff, 0x10, 0x7f};

        assertThat(repository.complete(id, "owner-a", 201, "application/json", body)).isTrue();

        assertThat(repository.findById(id)).get().satisfies(record -> {
            assertThat(record.state()).isEqualTo(State.COMPLETED);
            assertThat(record.status()).isEqualTo(201);
            assertThat(record.contentType()).isEqualTo("application/json");
            assertThat(record.body()).isEqualTo(body);
            assertThat(record.requestHash()).isEqualTo("hash-1");
            assertThat(record.expiresAt()).isEqualTo(NOW.plus(TTL));
        });
    }

    @Test
    void completeAndDeleteByAStaleOwnerAfterATakeoverMatchNothing() {
        String id = newId();
        repository.insertIfAbsent(inProgress(id, "owner-a", NOW));
        Instant later = NOW.plus(LEASE).plusSeconds(1);
        repository.takeOverIfLeaseExpired(id, "owner-b", later, later.plus(LEASE));

        assertThat(repository.complete(id, "owner-a", 201, "application/json", bytes("stale"))).isFalse();
        assertThat(repository.delete(id, "owner-a")).isFalse();

        assertThat(repository.findById(id)).get().satisfies(record -> {
            assertThat(record.state()).isEqualTo(State.IN_PROGRESS);
            assertThat(record.leaseOwner()).isEqualTo("owner-b");
            assertThat(record.body()).isNull();
        });
        assertThat(repository.complete(id, "owner-b", 201, "application/json", bytes("winner"))).isTrue();
    }

    @Test
    void aCompletedRecordIsNeverCompletedOrDeletedAgain() {
        String id = newId();
        repository.insertIfAbsent(inProgress(id, "owner-a", NOW));
        repository.complete(id, "owner-a", 201, "application/json", bytes("first"));

        assertThat(repository.complete(id, "owner-a", 500, "application/json", bytes("second"))).isFalse();
        assertThat(repository.delete(id, "owner-a")).isFalse();
        assertThat(repository.findById(id)).get().extracting(IdempotencyRecord::status).isEqualTo(201);
    }

    @Test
    void deleteByTheOwnerRemovesTheRecord() {
        String id = newId();
        repository.insertIfAbsent(inProgress(id, "owner-a", NOW));

        assertThat(repository.delete(id, "owner-a")).isTrue();
        assertThat(repository.findById(id)).isEmpty();
    }

    @Test
    void deleteIfExpiredRemovesOnlyARecordWhoseExpiryHasPassed() {
        String id = newId();
        repository.insertIfAbsent(inProgress(id, "owner-a", NOW));
        repository.complete(id, "owner-a", 201, "application/json", bytes("done"));
        Instant expiry = NOW.plus(TTL);

        assertThat(repository.deleteIfExpired(id, expiry.minusMillis(1))).isFalse();
        assertThat(repository.findById(id)).isPresent();

        assertThat(repository.deleteIfExpired(id, expiry)).isTrue();
        assertThat(repository.findById(id)).isEmpty();
        assertThat(repository.deleteIfExpired(id, expiry)).isFalse();
    }

    @Test
    void theTtlIndexOnExpiresAtExists() {
        IndexInfo ttl = mongoTemplate.indexOps(IdempotencyRecord.COLLECTION).getIndexInfo().stream()
                .filter(info -> info.getExpireAfter().isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no TTL index on " + IdempotencyRecord.COLLECTION));

        assertThat(ttl.getExpireAfter()).contains(Duration.ZERO);
        assertThat(ttl.getIndexFields()).singleElement()
                .satisfies(field -> assertThat(field.getKey()).isEqualTo("expiresAt"));
    }

    private static IdempotencyRecord inProgress(String id, String owner, Instant now) {
        return IdempotencyRecord.inProgress(id, "PUBLIC_VERIFY", "hash-1", owner, now, now.plus(LEASE), now.plus(TTL));
    }

    private static String newId() {
        return "it-" + UUID.randomUUID();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
