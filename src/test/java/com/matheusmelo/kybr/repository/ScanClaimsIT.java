package com.matheusmelo.kybr.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.document.MotivoRecusa;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** The scan job's claims: one winner, lease takeover, fenced writes, and the backlog it reports. */
class ScanClaimsIT extends MongoIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-08T15:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private ScanClaims claims;
    @Autowired
    private MongoTemplate mongo;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), Document.class);
    }

    @Test
    void claimsTheDueFileCountingAnAttemptAndHoldingALease() {
        Document file = insert(NOW.minusSeconds(1));

        Optional<Document> claimed = claims.claimNext(6, NOW, "dono-1", LEASE);

        assertThat(claimed).get().satisfies(c -> {
            assertThat(c.id()).isEqualTo(file.id());
            assertThat(c.estado()).isEqualTo(EstadoDocumento.VERIFICANDO);
            assertThat(c.tentativas()).isEqualTo(1);
            assertThat(c.leaseOwner()).isEqualTo("dono-1");
            assertThat(c.leaseUntil()).isEqualTo(NOW.plus(LEASE));
        });
        assertThat(claims.claimNext(6, NOW, "dono-2", LEASE)).isEmpty();
    }

    @Test
    void aFileNotYetDueIsLeftAlone() {
        insert(NOW.plusSeconds(1));

        assertThat(claims.claimNext(6, NOW, "dono-1", LEASE)).isEmpty();
    }

    @Test
    void theFileDueEarliestIsClaimedFirst() {
        insert(NOW.minusSeconds(10));
        Document earliest = insert(NOW.minusSeconds(60));

        assertThat(claims.claimNext(6, NOW, "dono-1", LEASE)).get().extracting(Document::id).isEqualTo(earliest.id());
    }

    @Test
    void twoConcurrentClaimsHaveExactlyOneWinner() throws Exception {
        insert(NOW.minusSeconds(1));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Document>> a = pool.submit(() -> {
                start.await();
                return claims.claimNext(6, NOW, "dono-a", LEASE);
            });
            Future<Optional<Document>> b = pool.submit(() -> {
                start.await();
                return claims.claimNext(6, NOW, "dono-b", LEASE);
            });
            start.countDown();

            assertThat(a.get().isPresent() ^ b.get().isPresent()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anExpiredLeaseIsTakenOverAndTheStaleOwnersWritesMatchNothing() {
        Document file = insert(NOW.minusSeconds(1));
        claims.claimNext(6, NOW, "velho", LEASE);

        assertThat(claims.claimNext(6, NOW.plus(LEASE).minusSeconds(1), "novo", LEASE)).isEmpty();
        Document takenOver = claims.claimNext(6, NOW.plus(LEASE).plusSeconds(1), "novo", LEASE).orElseThrow();

        assertThat(takenOver.tentativas()).isEqualTo(2);
        assertThat(claims.reschedule(file.id(), "velho", NOW.plusSeconds(60))).isFalse();
        assertThat(claims.refuse(file.id(), "velho", MotivoRecusa.VERIFICACAO_FALHOU)).isFalse();
        assertThat(claims.accept(file.id(), "velho", "k", "h", 1)).isFalse();
        assertThat(stored(file.id()).leaseOwner()).isEqualTo("novo");
    }

    @Test
    void aRescheduledFileIsDueAgainAtItsTimeWithoutALease() {
        Document file = insert(NOW.minusSeconds(1));
        claims.claimNext(6, NOW, "dono", LEASE);

        assertThat(claims.reschedule(file.id(), "dono", NOW.plus(Duration.ofMinutes(5)))).isTrue();

        Document stored = stored(file.id());
        assertThat(stored.estado()).isEqualTo(EstadoDocumento.RECEBIDO);
        assertThat(stored.leaseOwner()).isNull();
        assertThat(claims.claimNext(6, NOW.plus(Duration.ofMinutes(4)), "outro", LEASE)).isEmpty();
        assertThat(claims.claimNext(6, NOW.plus(Duration.ofMinutes(5)), "outro", LEASE)).isPresent();
    }

    @Test
    void aRefusedFileStopsCountingAndLosesItsQuarantineKey() {
        Document file = insert(NOW.minusSeconds(1));
        claims.claimNext(6, NOW, "dono", LEASE);

        assertThat(claims.refuse(file.id(), "dono", MotivoRecusa.CONTEUDO_NAO_PERMITIDO)).isTrue();

        Document stored = stored(file.id());
        assertThat(stored.estado()).isEqualTo(EstadoDocumento.RECUSADO);
        assertThat(stored.motivoRecusa()).isEqualTo(MotivoRecusa.CONTEUDO_NAO_PERMITIDO);
        assertThat(stored.ativo()).isNull();
        assertThat(stored.quarentenaKey()).isNull();
        assertThat(claims.claimNext(6, NOW.plus(Duration.ofDays(1)), "outro", LEASE)).isEmpty();
    }

    @Test
    void anAcceptedFileHasItsFinalObjectAndIsNeverClaimedAgain() {
        Document file = insert(NOW.minusSeconds(1));
        claims.claimNext(6, NOW, "dono", LEASE);

        assertThat(claims.accept(file.id(), "dono", "w/c/d", "ab", 42)).isTrue();
        claims.quarantineDeleted(file.id());

        Document stored = stored(file.id());
        assertThat(stored.estado()).isEqualTo(EstadoDocumento.ACEITO);
        assertThat(stored.storageKey()).isEqualTo("w/c/d");
        assertThat(stored.sha256()).isEqualTo("ab");
        assertThat(stored.bytesArmazenados()).isEqualTo(42);
        assertThat(stored.quarentenaKey()).isNull();
        assertThat(stored.ativo()).isTrue();
        assertThat(claims.claimNext(6, NOW.plus(Duration.ofDays(1)), "outro", LEASE)).isEmpty();
    }

    @Test
    void aFileAtTheAttemptCeilingIsNeverClaimedForAScanOnlyForItsRefusal() {
        Document file = insert(NOW.minusSeconds(1));
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(file.id())),
                new org.springframework.data.mongodb.core.query.Update().set(Document.TENTATIVAS, 6), Document.class);

        assertThat(claims.claimNext(6, NOW, "dono", LEASE)).isEmpty();
        Document exhausted = claims.claimExhausted(6, NOW, "dono", LEASE).orElseThrow();

        assertThat(exhausted.id()).isEqualTo(file.id());
        assertThat(exhausted.tentativas()).isEqualTo(6);
        assertThat(exhausted.leaseOwner()).isEqualTo("dono");
        assertThat(claims.claimExhausted(6, NOW, "outro", LEASE)).isEmpty();
    }

    @Test
    void theBacklogCountsWaitingAndClaimedFilesTheOldestAndTheStuck() {
        insert(NOW.minus(Duration.ofHours(30)));
        insert(NOW.minus(Duration.ofMinutes(20)));
        insert(NOW.minusSeconds(5));
        claims.claimNext(6, NOW, "dono", LEASE);

        ScanClaims.Backlog backlog = claims.backlog(NOW, Duration.ofHours(24));

        assertThat(backlog.recebidos()).isEqualTo(2);
        assertThat(backlog.verificando()).isEqualTo(1);
        assertThat(backlog.oldestPending()).isEqualTo(NOW.minus(Duration.ofHours(30)));
        assertThat(backlog.stuck()).isEqualTo(1);
    }

    @Test
    void anEmptyBacklogHasNoOldestFile() {
        assertThat(claims.backlog(NOW, Duration.ofHours(24))).isEqualTo(new ScanClaims.Backlog(0, 0, null, 0));
    }

    private Document insert(Instant due) {
        UUID id = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        byte[] hash = new byte[32];
        ThreadLocalRandom.current().nextBytes(hash);
        return mongo.insert(Document.recebido(id, UUID.randomUUID(), caseId, null, DocumentoTipo.RESPONSAVEL,
                FormatoEnvio.pdf, 1, "application/pdf", 100, HexFormat.of().formatHex(hash), due,
                "w/" + caseId + "/" + id, UUID.randomUUID()));
    }

    private Document stored(UUID id) {
        return mongo.findById(id, Document.class);
    }
}
