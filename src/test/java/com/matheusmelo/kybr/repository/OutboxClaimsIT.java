package com.matheusmelo.kybr.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.ResultadoEnvio;
import com.matheusmelo.kybr.model.link.TipoOutbox;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class OutboxClaimsIT extends MongoIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-07T15:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Set<TipoOutbox> ALL = Set.of(TipoOutbox.CONTATO_ALTERADO);

    @Autowired
    private OutboxClaims claims;
    @Autowired
    private MongoTemplate mongo;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), OutboxEvent.class);
    }

    @Test
    void claimsTheDueEventCountingAnAttemptAndHoldingALease() {
        OutboxEvent event = insert(NOW.minusSeconds(1));

        Optional<OutboxEvent> claimed = claims.claimNext(ALL, NOW, "dono-1", LEASE);

        assertThat(claimed).get().satisfies(c -> {
            assertThat(c.id()).isEqualTo(event.id());
            assertThat(c.tentativas()).isEqualTo(1);
            assertThat(c.leaseOwner()).isEqualTo("dono-1");
            assertThat(c.leaseUntil()).isEqualTo(NOW.plus(LEASE));
        });
        assertThat(claims.claimNext(ALL, NOW, "dono-2", LEASE)).isEmpty();
    }

    @Test
    void twoConcurrentClaimsHaveExactlyOneWinner() throws Exception {
        insert(NOW.minusSeconds(1));
        CountDownLatch start = new CountDownLatch(1);
        Callable<Optional<OutboxEvent>> claim = () -> {
            start.await();
            return claims.claimNext(ALL, NOW, UUID.randomUUID().toString(), LEASE);
        };

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Optional<OutboxEvent>> first = pool.submit(claim);
            Future<Optional<OutboxEvent>> second = pool.submit(claim);
            start.countDown();
            assertThat(first.get().isPresent() ^ second.get().isPresent()).isTrue();
        }
    }

    @Test
    void anEventNotYetDueOrOfADisabledTypeIsNotClaimed() {
        insert(NOW.plusSeconds(1));

        assertThat(claims.claimNext(ALL, NOW, "dono", LEASE)).isEmpty();

        insert(NOW.minusSeconds(1));
        assertThat(claims.claimNext(Set.of(), NOW, "dono", LEASE)).isEmpty();
    }

    @Test
    void anExpiredEventIsNotClaimed() {
        UUID id = UUID.randomUUID();
        mongo.insert(new OutboxEvent(id, TipoOutbox.CONTATO_ALTERADO, UUID.randomUUID(), new byte[] {1},
                NOW.minus(Duration.ofDays(31)), NOW.minusSeconds(1)));

        assertThat(claims.claimNext(ALL, NOW, "dono", LEASE)).isEmpty();
    }

    @Test
    void anEventStoredBeforeDeliveryExistedIsDue() {
        UUID id = UUID.randomUUID();
        mongo.getCollection(OutboxEvent.COLLECTION).insertOne(new Document("_id", id)
                .append("tipo", "CONTATO_ALTERADO").append("case_id", UUID.randomUUID())
                .append("payload_cifrado", new byte[] {1}).append("criado_em", Date.from(NOW.minusSeconds(5)))
                .append("pendente", true).append("expira_em", Date.from(NOW.plus(Duration.ofDays(1)))));

        assertThat(claims.claimNext(ALL, NOW, "dono", LEASE)).get().extracting(OutboxEvent::id).isEqualTo(id);
    }

    @Test
    void anExpiredLeaseCanBeTakenOverAndTheStaleOwnersWritesMatchNothing() {
        OutboxEvent event = insert(NOW.minusSeconds(1));
        claims.claimNext(ALL, NOW, "antigo", LEASE);
        Instant later = NOW.plus(LEASE).plusSeconds(1);

        assertThat(claims.claimNext(ALL, later, "novo", LEASE)).get().extracting(OutboxEvent::tentativas).isEqualTo(2);

        assertThat(claims.markProcessed(event.id(), "antigo", later, ResultadoEnvio.enviado)).isFalse();
        assertThat(claims.reschedule(event.id(), "antigo", later.plusSeconds(60))).isFalse();
        assertThat(claims.markFailed(event.id(), "antigo", later)).isFalse();
        assertThat(mongo.findById(event.id(), OutboxEvent.class)).satisfies(e -> {
            assertThat(e.pendente()).isTrue();
            assertThat(e.leaseOwner()).isEqualTo("novo");
        });
    }

    @Test
    void markingProcessedLeavesTheQueueAndTheCoalescingIndex() {
        OutboxEvent event = insert(NOW.minusSeconds(1));
        claims.claimNext(ALL, NOW, "dono", LEASE);

        assertThat(claims.markProcessed(event.id(), "dono", NOW, ResultadoEnvio.limitado)).isTrue();

        OutboxEvent stored = mongo.findById(event.id(), OutboxEvent.class);
        assertThat(stored.pendente()).isFalse();
        assertThat(stored.processadoEm()).isEqualTo(NOW);
        assertThat(stored.resultadoEnvio()).isEqualTo(ResultadoEnvio.limitado);
        assertThat(stored.leaseOwner()).isNull();
        assertThat(rawPendente(event.id())).isFalse();
        // the same case can queue a new notification: the unique index covers pending events only
        mongo.insert(new OutboxEvent(UUID.randomUUID(), TipoOutbox.CONTATO_ALTERADO, event.caseId(), new byte[] {2},
                NOW, NOW.plus(Duration.ofDays(30))));
    }

    @Test
    void reschedulingReleasesTheLeaseUntilTheNextAttempt() {
        OutboxEvent event = insert(NOW.minusSeconds(1));
        claims.claimNext(ALL, NOW, "dono", LEASE);

        assertThat(claims.reschedule(event.id(), "dono", NOW.plus(Duration.ofMinutes(1)))).isTrue();

        assertThat(claims.claimNext(ALL, NOW.plusSeconds(59), "outro", LEASE)).isEmpty();
        assertThat(claims.claimNext(ALL, NOW.plusSeconds(60), "outro", LEASE)).get()
                .extracting(OutboxEvent::tentativas).isEqualTo(2);
    }

    @Test
    void markingFailedLeavesTheQueueForGood() {
        OutboxEvent event = insert(NOW.minusSeconds(1));
        claims.claimNext(ALL, NOW, "dono", LEASE);

        assertThat(claims.markFailed(event.id(), "dono", NOW)).isTrue();

        OutboxEvent stored = mongo.findById(event.id(), OutboxEvent.class);
        assertThat(stored.falhouEm()).isEqualTo(NOW);
        assertThat(stored.pendente()).isFalse();
        assertThat(rawPendente(event.id())).isFalse();
        assertThat(claims.claimNext(ALL, NOW.plus(Duration.ofDays(1)), "outro", LEASE)).isEmpty();
    }

    private OutboxEvent insert(Instant due) {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), TipoOutbox.CONTATO_ALTERADO, UUID.randomUUID(),
                new byte[] {1}, due, due.plus(Duration.ofDays(30)));
        return mongo.insert(event);
    }

    private boolean rawPendente(UUID id) {
        return mongo.getCollection(OutboxEvent.COLLECTION).find(new Document("_id", id)).first().getBoolean("pendente");
    }
}
