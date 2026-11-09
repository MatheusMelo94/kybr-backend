package com.matheusmelo.kybr.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.document.DocumentRequest;
import com.matheusmelo.kybr.model.link.Contato;
import com.matheusmelo.kybr.model.link.EstadoRetencao;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.Retencao;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class RetentionClaimsIT extends MongoIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-07T15:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private RetentionClaims claims;
    @Autowired
    private LinkCaseRepository cases;
    @Autowired
    private DocumentRequestRepository requests;
    @Autowired
    private MongoTemplate mongo;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), LinkCase.class);
        mongo.remove(new Query(), DocumentRequest.class);
    }

    @Test
    void theClaimRevokesTheTokenInTheSameAtomicUpdate() {
        LinkCase due = save(NOW.minusSeconds(1), null, null);

        Optional<LinkCase> claimed = claims.claimNext(NOW, "dono", LEASE);

        assertThat(claimed).get().satisfies(c -> {
            assertThat(c.id()).isEqualTo(due.id());
            assertThat(c.retencao().estado()).isEqualTo(EstadoRetencao.EM_ANDAMENTO);
            assertThat(c.retencao().leaseOwner()).isEqualTo("dono");
            assertThat(c.retencao().leaseUntil()).isEqualTo(NOW.plus(LEASE));
            assertThat(c.tokenRevogadoEm()).isEqualTo(NOW);
        });
        assertThat(cases.findById(due.id()).orElseThrow().tokenRevogadoEm()).isEqualTo(NOW);
    }

    @Test
    void anEarlierRevocationIsKept() {
        save(NOW.minusSeconds(1), null, NOW.minus(Duration.ofDays(1)));

        assertThat(claims.claimNext(NOW, "dono", LEASE)).get().extracting(LinkCase::tokenRevogadoEm)
                .isEqualTo(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    void aCaseNotYetDueOrWithoutADateIsNotClaimed() {
        save(NOW.plusSeconds(1), null, null);
        save(null, null, null);

        assertThat(claims.claimNext(NOW, "dono", LEASE)).isEmpty();
    }

    @Test
    void aCaseDueExactlyNowIsClaimed() {
        save(NOW, null, null);

        assertThat(claims.claimNext(NOW, "dono", LEASE)).isPresent();
    }

    @Test
    void twoConcurrentClaimsHaveExactlyOneWinner() throws Exception {
        save(NOW.minusSeconds(1), null, null);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Optional<LinkCase>> claim = () -> {
            start.await();
            return claims.claimNext(NOW, UUID.randomUUID().toString(), LEASE);
        };

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Optional<LinkCase>> first = pool.submit(claim);
            Future<Optional<LinkCase>> second = pool.submit(claim);
            start.countDown();
            assertThat(first.get().isPresent() ^ second.get().isPresent()).isTrue();
        }
    }

    @Test
    void anExpiredLeaseIsTakenOverAndTheStaleOwnerCannotConclude() {
        LinkCase due = save(NOW.minusSeconds(1), null, null);
        claims.claimNext(NOW, "antigo", LEASE);
        assertThat(claims.claimNext(NOW.plusSeconds(10), "outro", LEASE)).isEmpty();
        Instant later = NOW.plus(LEASE).plusSeconds(1);

        assertThat(claims.claimNext(later, "novo", LEASE)).isPresent();

        assertThat(claims.conclude(due.id(), "antigo", later)).isFalse();
        assertThat(claims.conclude(due.id(), "novo", later)).isTrue();
    }

    @Test
    void concludingClearsTheContactAndIsNeverClaimedAgain() {
        LinkCase due = save(NOW.minusSeconds(1), new Contato(new byte[] {1, 2}, "m•••@example.test", NOW), null);
        claims.claimNext(NOW, "dono", LEASE);

        assertThat(claims.conclude(due.id(), "dono", NOW.plusSeconds(1))).isTrue();

        LinkCase stored = cases.findById(due.id()).orElseThrow();
        assertThat(stored.contato()).isNull();
        assertThat(stored.retencao()).isEqualTo(new Retencao(EstadoRetencao.CONCLUIDA, null, null, NOW.plusSeconds(1)));
        assertThat(stored.tokenRevogadoEm()).isEqualTo(NOW);
        assertThat(claims.claimNext(NOW.plus(Duration.ofDays(400)), "outro", LEASE)).isEmpty();
    }

    @Test
    void closesTheCasesOpenDocumentRequestsOnly() {
        UUID caseId = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        DocumentRequest open = request(caseId, null);
        DocumentRequest closed = request(caseId, NOW.minus(Duration.ofDays(1)));
        DocumentRequest otherCase = request(other, null);

        assertThat(claims.closeDocumentRequests(caseId, NOW)).isEqualTo(1);

        assertThat(requests.findById(open.id()).orElseThrow().encerradoEm()).isEqualTo(NOW);
        assertThat(requests.findById(closed.id()).orElseThrow().encerradoEm()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(requests.findById(otherCase.id()).orElseThrow().encerradoEm()).isNull();
    }

    private LinkCase save(Instant retencaoAte, Contato contato, Instant tokenRevogadoEm) {
        return cases.save(TestCases.builder(TestWorkspaces.builder().build()).retencaoAte(retencaoAte).contato(contato)
                .tokenRevogadoEm(tokenRevogadoEm).build().linkCase());
    }

    private DocumentRequest request(UUID caseId, Instant encerradoEm) {
        return requests.save(new DocumentRequest(UUID.randomUUID(), UUID.randomUUID(), caseId,
                TestCases.hash(TestCases.newToken()), List.of(DocumentoTipo.values()[0]), NOW.minus(Duration.ofDays(2)),
                NOW.plus(Duration.ofDays(28)), null, encerradoEm));
    }
}
