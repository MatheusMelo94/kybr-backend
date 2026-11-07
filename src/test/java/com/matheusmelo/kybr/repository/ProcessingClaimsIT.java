package com.matheusmelo.kybr.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.EstadoProcessamento;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.MotivoRevisao;
import com.matheusmelo.kybr.model.link.Processamento;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.link.Revisao;
import com.matheusmelo.kybr.model.link.Variante;
import com.matheusmelo.kybr.model.workspace.Workspace;
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

class ProcessingClaimsIT extends MongoIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-07T15:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(60);

    @Autowired
    private ProcessingClaims claims;
    @Autowired
    private LinkCaseRepository cases;

    private final Workspace workspace = TestWorkspaces.builder().build();

    @BeforeEach
    void clean() {
        cases.deleteAll();
    }

    @Test
    void twoConcurrentClaimsOfOneCaseHaveExactlyOneWinner() throws Exception {
        LinkCase pending = save(Processamento.pendente(NOW.minusSeconds(1)));
        CountDownLatch start = new CountDownLatch(1);
        Callable<Optional<LinkCase>> claim = () -> {
            start.await();
            return claims.claim(pending.id(), NOW, UUID.randomUUID().toString(), LEASE);
        };

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Optional<LinkCase>> first = pool.submit(claim);
            Future<Optional<LinkCase>> second = pool.submit(claim);
            start.countDown();
            assertThat(List.of(first.get().isPresent(), second.get().isPresent())).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void aClaimTakesTheLeaseAndCountsTheAttempt() {
        LinkCase pending = save(Processamento.pendente(NOW.minusSeconds(1)));

        LinkCase claimed = claims.claim(pending.id(), NOW, "owner-a", LEASE).orElseThrow();

        assertThat(claimed.processamento().estado()).isEqualTo(EstadoProcessamento.EM_ANDAMENTO);
        assertThat(claimed.processamento().leaseOwner()).isEqualTo("owner-a");
        assertThat(claimed.processamento().leaseUntil()).isEqualTo(NOW.plus(LEASE));
        assertThat(claimed.processamento().tentativas()).isEqualTo(1);
    }

    @Test
    void aCaseNotYetDueOrUnderALiveLeaseCantBeClaimed() {
        LinkCase future = save(Processamento.pendente(NOW.plusSeconds(5)));
        LinkCase leased = save(new Processamento(EstadoProcessamento.EM_ANDAMENTO, 1, NOW, "owner-a",
                NOW.plusSeconds(1), null));

        assertThat(claims.claim(future.id(), NOW, "owner-b", LEASE)).isEmpty();
        assertThat(claims.claim(leased.id(), NOW, "owner-b", LEASE)).isEmpty();
        assertThat(claims.claimNext(NOW, "owner-b", LEASE)).isEmpty();
    }

    @Test
    void anExpiredLeaseIsReclaimable() {
        LinkCase leased = save(new Processamento(EstadoProcessamento.EM_ANDAMENTO, 1, NOW.minusSeconds(120),
                "crashed", NOW.minusSeconds(1), null));

        LinkCase reclaimed = claims.claimNext(NOW, "owner-b", LEASE).orElseThrow();

        assertThat(reclaimed.id()).isEqualTo(leased.id());
        assertThat(reclaimed.processamento().leaseOwner()).isEqualTo("owner-b");
        assertThat(reclaimed.processamento().tentativas()).isEqualTo(2);
    }

    @Test
    void claimNextTakesTheEarliestDueCase() {
        save(Processamento.pendente(NOW.minusSeconds(1)));
        LinkCase earliest = save(Processamento.pendente(NOW.minusSeconds(30)));
        save(new Processamento(EstadoProcessamento.REVISAO_MANUAL, 3, NOW.minusSeconds(60), null, null,
                MotivoRevisao.PROVEDOR_INDISPONIVEL));

        assertThat(claims.claimNext(NOW, "owner-a", LEASE).orElseThrow().id()).isEqualTo(earliest.id());
    }

    @Test
    void aWriteWithAStaleOwnerMatchesNothing() {
        LinkCase pending = save(Processamento.pendente(NOW.minusSeconds(1)));
        claims.claim(pending.id(), NOW, "owner-a", LEASE).orElseThrow();
        Resultado resultado = new Resultado("Loja Exemplo Ltda", NOW, null, null,
                List.of(ItemResultado.of(CodigoResultado.SITUACAO_ATIVA)), null, null);

        assertThat(claims.complete(pending.id(), "owner-b", EstadoCaso.approved, resultado, UUID.randomUUID(), null,
                null)).isFalse();
        assertThat(claims.reschedule(pending.id(), "owner-b", NOW.plusSeconds(5), false)).isFalse();
        assertThat(claims.toManualReview(pending.id(), "owner-b", MotivoRevisao.ORCAMENTO, null, null)).isFalse();
        assertThat(cases.findById(pending.id()).orElseThrow().processamento().leaseOwner()).isEqualTo("owner-a");
    }

    @Test
    void completeWritesTheVerdictAndReleasesTheLease() {
        LinkCase pending = save(Processamento.pendente(NOW.minusSeconds(1)));
        claims.claim(pending.id(), NOW, "owner-a", LEASE).orElseThrow();
        UUID verificationId = UUID.randomUUID();
        Instant prazo = NOW.plus(Duration.ofDays(2));
        Resultado resultado = new Resultado("Loja Exemplo Ltda", NOW, null, prazo, null,
                List.of(ItemResultado.of(CodigoResultado.EMPRESA_RECENTE)), null);

        assertThat(claims.complete(pending.id(), "owner-a", EstadoCaso.pending, resultado, verificationId, prazo,
                new Revisao(prazo))).isTrue();

        LinkCase done = cases.findById(pending.id()).orElseThrow();
        assertThat(done.estado()).isEqualTo(EstadoCaso.pending);
        assertThat(done.resultado()).isEqualTo(resultado);
        assertThat(done.verificacaoId()).isEqualTo(verificationId);
        assertThat(done.prazoRevisao()).isEqualTo(prazo);
        assertThat(done.revisao()).isEqualTo(new Revisao(prazo));
        assertThat(done.processamento().estado()).isEqualTo(EstadoProcessamento.CONCLUIDO);
        assertThat(done.processamento().leaseOwner()).isNull();
        assertThat(done.processamento().tentativas()).isEqualTo(1);
    }

    @Test
    void rescheduleReturnsTheCaseToPendingAndCanRefundTheAttempt() {
        LinkCase pending = save(Processamento.pendente(NOW.minusSeconds(1)));
        claims.claim(pending.id(), NOW, "owner-a", LEASE).orElseThrow();

        assertThat(claims.reschedule(pending.id(), "owner-a", NOW.plusSeconds(30), true)).isTrue();

        Processamento processamento = cases.findById(pending.id()).orElseThrow().processamento();
        assertThat(processamento.estado()).isEqualTo(EstadoProcessamento.PENDENTE);
        assertThat(processamento.proximaTentativa()).isEqualTo(NOW.plusSeconds(30));
        assertThat(processamento.leaseOwner()).isNull();
        assertThat(processamento.tentativas()).isZero();
    }

    @Test
    void manualReviewKeepsTheSellersViewInProcessing() {
        LinkCase pending = save(Processamento.pendente(NOW.minusSeconds(1)));
        claims.claim(pending.id(), NOW, "owner-a", LEASE).orElseThrow();
        Resultado variant = new Resultado(null, null, Variante.DADOS_NAO_ENCONTRADOS, null, null, null, null);

        assertThat(claims.toManualReview(pending.id(), "owner-a", null, Variante.DADOS_NAO_ENCONTRADOS, variant))
                .isTrue();

        LinkCase review = cases.findById(pending.id()).orElseThrow();
        assertThat(review.estado()).isEqualTo(EstadoCaso.processing);
        assertThat(review.variante()).isEqualTo(Variante.DADOS_NAO_ENCONTRADOS);
        assertThat(review.resultado()).isEqualTo(variant);
        assertThat(review.processamento().estado()).isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
        assertThat(review.processamento().leaseOwner()).isNull();
    }

    private LinkCase save(Processamento processamento) {
        return cases.save(TestCases.builder(workspace).processamento(processamento).build().linkCase());
    }
}
