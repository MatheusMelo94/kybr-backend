package com.matheusmelo.kybr.service.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.EstadoProcessamento;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.MotivoRevisao;
import com.matheusmelo.kybr.model.link.OrigemSeller;
import com.matheusmelo.kybr.model.link.Processamento;
import com.matheusmelo.kybr.model.link.Regime;
import com.matheusmelo.kybr.model.link.Seller;
import com.matheusmelo.kybr.model.link.StatusSeller;
import com.matheusmelo.kybr.model.link.Variante;
import com.matheusmelo.kybr.model.link.Verification;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.ProcessingClaims;
import com.matheusmelo.kybr.repository.SellerRepository;
import com.matheusmelo.kybr.repository.SellerUpserts;
import com.matheusmelo.kybr.repository.VerificationRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.company.BasicCompany;
import com.matheusmelo.kybr.service.company.CompanyDataProvider;
import com.matheusmelo.kybr.service.company.CompanyDataResult;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Found;
import com.matheusmelo.kybr.service.company.CompanyDataResult.RateLimited;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unavailable;
import com.matheusmelo.kybr.service.company.CompanyRecord;
import com.matheusmelo.kybr.service.company.ProviderBudgets;
import com.matheusmelo.kybr.service.company.ProviderBudgets.Budget;
import com.matheusmelo.kybr.service.company.ProviderBudgets.Decision;
import com.matheusmelo.kybr.service.company.StubCompanyDataProvider;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.MovableClock;
import com.matheusmelo.kybr.support.link.FakeCompanyDataProvider;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(OutputCaptureExtension.class)
class VerificationWorkerIT extends MongoIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-07T15:00:00Z");
    private static final Instant CRIADO_EM = NOW.minusSeconds(2);
    private static final String PARTNER = "Maria Exemplo Teste";

    @Autowired
    private ProcessingClaims claims;
    @Autowired
    private LinkCaseRepository cases;
    @Autowired
    private WorkspaceRepository workspaces;
    @Autowired
    private VerificationRepository verifications;
    @Autowired
    private SellerRepository sellers;
    @Autowired
    private SellerUpserts sellerUpserts;
    @Autowired
    private MongoTransactionManager transactionManager;
    @Autowired
    private KybrProperties properties;
    @Autowired
    private MongoTemplate mongo;

    private final MovableClock clock = new MovableClock(NOW);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ProviderBudgets budgets = mock(ProviderBudgets.class);
    private final FakeCompanyDataProvider fake = new FakeCompanyDataProvider();
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = workspaces.save(TestWorkspaces.builder().slug("loja-" + UUID.randomUUID().toString().substring(0, 8))
                .build());
        when(budgets.canSpendD3(anyString())).thenReturn(Decision.ALLOWED);
        when(budgets.acquireProviderCall()).thenReturn(Optional.empty());
    }

    @Test
    void anApprovedCaseGetsItsResultVerificationAndSellerStatus() {
        LinkCase linkCase = newCase("12345678000195", PARTNER);

        assertThat(worker(stub()).process(linkCase.id())).isTrue();

        LinkCase done = cases.findById(linkCase.id()).orElseThrow();
        assertThat(done.estado()).isEqualTo(EstadoCaso.approved);
        assertThat(done.processamento().estado()).isEqualTo(EstadoProcessamento.CONCLUIDO);
        assertThat(done.resultado().confirmacoes()).extracting(ItemResultado::codigo).containsExactly(
                CodigoResultado.SITUACAO_ATIVA, CodigoResultado.RESPONSAVEL_CONFERE, CodigoResultado.IE_ATIVA);
        assertThat(done.resultado().consultadoEm()).isEqualTo(NOW);
        List<Verification> written = verifications.findByWorkspaceIdAndCaseId(workspace.id(), linkCase.id());
        assertThat(written).singleElement().satisfies(verification -> {
            assertThat(verification.id()).isEqualTo(done.verificacaoId());
            assertThat(verification.politicaVersao()).isEqualTo(1);
        });
        Seller seller = sellers.findByWorkspaceIdAndCnpj(workspace.id(), "12345678000195").orElseThrow();
        assertThat(seller.status()).isEqualTo(StatusSeller.aprovado);
        assertThat(seller.verificadoEm()).isEqualTo(NOW);
        assertThat(seller.ultimaVerificacaoId()).isEqualTo(done.verificacaoId());
        assertThat(seller.origem()).isEqualTo(OrigemSeller.link);
        verify(budgets).bookD3(workspace.slug());
        assertThat(meters.get("kybr.verification.duration").tag("resultado", "approved").timer().count()).isEqualTo(1);
    }

    @Test
    void pendingAndBlockedCasesGetTheirResultAndSellerStatus() {
        LinkCase pending = newCase("98765432000198", PARTNER);
        LinkCase blocked = newCase("11222333000181", PARTNER);
        VerificationWorker worker = worker(stub());

        worker.process(pending.id());
        worker.process(blocked.id());

        LinkCase pendingDone = cases.findById(pending.id()).orElseThrow();
        assertThat(pendingDone.estado()).isEqualTo(EstadoCaso.pending);
        assertThat(pendingDone.prazoRevisao()).isEqualTo(CRIADO_EM.plus(Duration.ofDays(2)));
        assertThat(pendingDone.revisao().slaAte()).isEqualTo(pendingDone.prazoRevisao());
        assertThat(cases.findById(blocked.id()).orElseThrow().estado()).isEqualTo(EstadoCaso.blocked);
        assertThat(sellers.findByWorkspaceIdAndCnpj(workspace.id(), "98765432000198").orElseThrow().status())
                .isEqualTo(StatusSeller.revisao);
        assertThat(sellers.findByWorkspaceIdAndCnpj(workspace.id(), "11222333000181").orElseThrow().status())
                .isEqualTo(StatusSeller.bloqueado);
    }

    @Test
    void aMissingCompanyGoesToManualReviewWithTheNoDataVariant() {
        LinkCase linkCase = newCase("71438562000160", PARTNER);

        worker(stub()).process(linkCase.id());

        LinkCase review = cases.findById(linkCase.id()).orElseThrow();
        assertThat(review.estado()).isEqualTo(EstadoCaso.processing);
        assertThat(review.variante()).isEqualTo(Variante.DADOS_NAO_ENCONTRADOS);
        assertThat(review.resultado().variante()).isEqualTo(Variante.DADOS_NAO_ENCONTRADOS);
        assertThat(review.resultado().razaoSocial()).isNull();
        assertThat(review.resultado().consultadoEm()).isNull();
        assertThat(review.processamento().estado()).isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
        verify(budgets).recordNoData(workspace.slug());
        verify(budgets).bookD3(workspace.slug());
        assertThat(meters.get("kybr.verification.no_data").tag("slug", workspace.slug()).counter().count())
                .isEqualTo(1);
        assertThat(verifications.findByWorkspaceIdAndCaseId(workspace.id(), linkCase.id())).isEmpty();
    }

    @Test
    void anUnavailableProviderIsRetriedWithBackoffThenGoesToManualReview(CapturedOutput output) {
        LinkCase linkCase = newCase("52000015000153", PARTNER);
        VerificationWorker worker = worker(stub());

        worker.process(linkCase.id());
        assertThat(processamento(linkCase)).satisfies(p -> {
            assertThat(p.estado()).isEqualTo(EstadoProcessamento.PENDENTE);
            assertThat(p.proximaTentativa()).isEqualTo(NOW.plusSeconds(5));
            assertThat(p.tentativas()).isEqualTo(1);
        });
        assertThat(worker.process(linkCase.id())).as("not due yet").isFalse();

        clock.advance(Duration.ofSeconds(5));
        worker.process(linkCase.id());
        assertThat(processamento(linkCase).proximaTentativa()).isEqualTo(NOW.plusSeconds(35));

        clock.advance(Duration.ofSeconds(30));
        worker.process(linkCase.id());
        assertThat(processamento(linkCase)).satisfies(p -> {
            assertThat(p.estado()).isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
            assertThat(p.motivoRevisao()).isEqualTo(MotivoRevisao.PROVEDOR_INDISPONIVEL);
            assertThat(p.tentativas()).isEqualTo(3);
        });
        assertThat(cases.findById(linkCase.id()).orElseThrow().estado()).isEqualTo(EstadoCaso.processing);
        assertThat(meters.get("kybr.verification.failed").tag("motivo", "PROVEDOR_INDISPONIVEL").counter().count())
                .isEqualTo(1);
        assertThat(output.getAll()).contains("event=verification_failed");
    }

    @Test
    void anUnsupportedCnpjGoesToManualReviewWithoutRetry(CapturedOutput output) {
        LinkCase linkCase = newCase("5200A000000100", PARTNER);

        worker(stub()).process(linkCase.id());

        assertThat(processamento(linkCase)).satisfies(p -> {
            assertThat(p.estado()).isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
            assertThat(p.motivoRevisao()).isEqualTo(MotivoRevisao.PROVEDOR_NAO_SUPORTA);
            assertThat(p.tentativas()).isEqualTo(1);
        });
        assertThat(cases.findById(linkCase.id()).orElseThrow().variante()).isNull();
        assertThat(output.getAll()).contains("event=provider_unsupported_cnpj").doesNotContain("5200A000000100");
    }

    @Test
    void anIncompleteRecordGoesToManualReviewWithoutAVariant(CapturedOutput output) {
        String cnpj = "45997418000153";
        CompanyRecord active = FakeCompanyDataProvider.activeCompany();
        fake.scriptFullCheck(cnpj, new Found<>(new CompanyRecord(active.razaoSocial(), active.municipio(), active.uf(),
                active.cnaeCodigo(), active.cnaeDescricao(), null, active.dataAbertura(), Regime.OUTRO,
                active.inscricoesEstaduais(), active.qsaNomes())));
        LinkCase linkCase = newCase(cnpj, PARTNER);

        worker(fake).process(linkCase.id());

        assertThat(processamento(linkCase).motivoRevisao()).isEqualTo(MotivoRevisao.DADOS_INCOMPLETOS);
        assertThat(cases.findById(linkCase.id()).orElseThrow().variante()).isNull();
        assertThat(output.getAll()).contains("event=provider_incomplete_record");
    }

    @Test
    void aRecordWhoseStateIsNotAStateCodeGoesToManualReviewAsIncomplete(CapturedOutput output) {
        String cnpj = "45997418000315";
        CompanyRecord active = FakeCompanyDataProvider.activeCompany();
        fake.scriptFullCheck(cnpj, new Found<>(new CompanyRecord(active.razaoSocial(), active.municipio(), null,
                active.cnaeCodigo(), active.cnaeDescricao(), active.situacao(), active.dataAbertura(), Regime.OUTRO,
                active.inscricoesEstaduais(), active.qsaNomes())));
        LinkCase linkCase = newCase(cnpj, PARTNER);

        worker(fake).process(linkCase.id());

        assertThat(processamento(linkCase).motivoRevisao()).isEqualTo(MotivoRevisao.DADOS_INCOMPLETOS);
        assertThat(cases.findById(linkCase.id()).orElseThrow().resultado()).isNull();
        assertThat(output.getAll()).contains("event=provider_incomplete_record");
    }

    @Test
    void anOpeningDateInTheFutureIsADataAnomalyThatGoesToManualReview(CapturedOutput output) {
        String cnpj = "45997418000234";
        CompanyRecord active = FakeCompanyDataProvider.activeCompany();
        fake.scriptFullCheck(cnpj, new Found<>(new CompanyRecord(active.razaoSocial(), active.municipio(), active.uf(),
                active.cnaeCodigo(), active.cnaeDescricao(), active.situacao(), java.time.LocalDate.of(2026, 11, 8),
                Regime.OUTRO, active.inscricoesEstaduais(), active.qsaNomes())));
        LinkCase linkCase = newCase(cnpj, "Maria Exemplo");

        worker(fake).process(linkCase.id());

        assertThat(processamento(linkCase)).satisfies(p -> {
            assertThat(p.estado()).isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
            assertThat(p.motivoRevisao()).isEqualTo(MotivoRevisao.DADOS_INCOMPLETOS);
        });
        LinkCase stored = cases.findById(linkCase.id()).orElseThrow();
        assertThat(stored.estado()).isEqualTo(EstadoCaso.processing);
        assertThat(stored.resultado()).isNull();
        assertThat(verifications.findByWorkspaceIdAndCaseId(workspace.id(), linkCase.id())).isEmpty();
        assertThat(output.getAll()).contains("event=provider_anomalous_record");
    }

    @Test
    void anOpeningDateOfTodayInSaoPauloIsStillDecided() {
        String cnpj = "45997418000315";
        CompanyRecord active = FakeCompanyDataProvider.activeCompany();
        fake.scriptFullCheck(cnpj, new Found<>(new CompanyRecord(active.razaoSocial(), active.municipio(), active.uf(),
                active.cnaeCodigo(), active.cnaeDescricao(), active.situacao(), java.time.LocalDate.of(2026, 11, 7),
                Regime.OUTRO, active.inscricoesEstaduais(), active.qsaNomes())));
        LinkCase linkCase = newCase(cnpj, "Maria Exemplo");

        worker(fake).process(linkCase.id());

        assertThat(cases.findById(linkCase.id()).orElseThrow().estado()).isEqualTo(EstadoCaso.pending);
    }

    @Test
    void unavailableThenFoundIsApprovedOnTheSecondAttempt() {
        String cnpj = "52000008000151";
        fake.scriptFullCheck(cnpj, new Unavailable<>(), new Found<>(FakeCompanyDataProvider.activeCompany()));
        LinkCase linkCase = newCase(cnpj, "Maria Exemplo");
        VerificationWorker worker = worker(fake);

        worker.process(linkCase.id());
        clock.advance(Duration.ofSeconds(5));
        worker.process(linkCase.id());

        LinkCase done = cases.findById(linkCase.id()).orElseThrow();
        assertThat(done.estado()).isEqualTo(EstadoCaso.approved);
        assertThat(done.processamento().tentativas()).isEqualTo(2);
    }

    @Test
    void aProviderRateLimitUsesAnAttemptAndReschedulesAtItsRetryAfter() {
        String cnpj = "52000009000104";
        fake.scriptFullCheck(cnpj, new RateLimited<>(Duration.ofSeconds(30)),
                new Found<>(FakeCompanyDataProvider.activeCompany()));
        LinkCase linkCase = newCase(cnpj, "Maria Exemplo");
        VerificationWorker worker = worker(fake);

        worker.process(linkCase.id());
        assertThat(processamento(linkCase)).satisfies(p -> {
            assertThat(p.estado()).isEqualTo(EstadoProcessamento.PENDENTE);
            assertThat(p.proximaTentativa()).isEqualTo(NOW.plusSeconds(30));
            assertThat(p.tentativas()).isEqualTo(1);
        });

        clock.advance(Duration.ofSeconds(30));
        worker.process(linkCase.id());
        assertThat(cases.findById(linkCase.id()).orElseThrow().estado()).isEqualTo(EstadoCaso.approved);
    }

    @Test
    void aProviderThatKeepsAnsweringRateLimitedGoesToManualReviewAfterTheMaximumAttempts(CapturedOutput output) {
        String cnpj = "52000010000111";
        fake.scriptFullCheck(cnpj, new RateLimited<>(Duration.ofMillis(200)));
        LinkCase linkCase = newCase(cnpj, PARTNER);
        VerificationWorker worker = worker(fake);

        worker.process(linkCase.id());
        assertThat(processamento(linkCase).proximaTentativa()).as("the backoff, longer than the retry-after")
                .isEqualTo(NOW.plusSeconds(5));
        clock.advance(Duration.ofSeconds(5));
        worker.process(linkCase.id());
        clock.advance(Duration.ofSeconds(30));
        worker.process(linkCase.id());
        clock.advance(Duration.ofMinutes(2));
        assertThat(worker.process(linkCase.id())).as("nothing left to claim").isFalse();

        assertThat(fake.fullCheckCalls(cnpj)).isEqualTo(3);
        assertThat(processamento(linkCase)).satisfies(p -> {
            assertThat(p.estado()).isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
            assertThat(p.motivoRevisao()).isEqualTo(MotivoRevisao.PROVEDOR_INDISPONIVEL);
        });
        assertThat(meters.get("kybr.verification.failed").tag("motivo", "PROVEDOR_INDISPONIVEL").counter().count())
                .isEqualTo(1);
        assertThat(output.getAll()).contains("event=verification_failed");
    }

    @Test
    void anUnexpectedFailureAfterTheClaimIsRetriedThenGoesToManualReviewBookingOncePerProviderCall(
            CapturedOutput output) {
        String cnpj = "52000011000156";
        fake.scriptFullCheck(cnpj, new Found<>(FakeCompanyDataProvider.activeCompany()));
        ChecksEvaluator broken = mock(ChecksEvaluator.class);
        when(broken.evaluate(any(), anyString(), anyInt())).thenThrow(new IllegalStateException("synthetic defect"));
        LinkCase linkCase = newCase(cnpj, PARTNER);
        VerificationWorker worker = worker(fake, broken);

        assertThat(worker.process(linkCase.id())).isTrue();
        assertThat(processamento(linkCase)).satisfies(p -> {
            assertThat(p.estado()).isEqualTo(EstadoProcessamento.PENDENTE);
            assertThat(p.proximaTentativa()).isEqualTo(NOW.plusSeconds(5));
            assertThat(p.tentativas()).isEqualTo(1);
        });
        clock.advance(Duration.ofSeconds(5));
        worker.process(linkCase.id());
        clock.advance(Duration.ofSeconds(30));
        worker.process(linkCase.id());
        clock.advance(Duration.ofMinutes(10));
        assertThat(worker.process(linkCase.id())).as("nothing left to claim").isFalse();

        assertThat(fake.fullCheckCalls(cnpj)).isEqualTo(3);
        verify(budgets, times(3)).bookD3(workspace.slug());
        assertThat(processamento(linkCase)).satisfies(p -> {
            assertThat(p.estado()).isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
            assertThat(p.motivoRevisao()).isEqualTo(MotivoRevisao.FALHA_PROCESSAMENTO);
        });
        assertThat(cases.findById(linkCase.id()).orElseThrow().estado()).isEqualTo(EstadoCaso.processing);
        assertThat(verifications.findByWorkspaceIdAndCaseId(workspace.id(), linkCase.id())).isEmpty();
        assertThat(meters.get("kybr.verification.failed").tag("motivo", "FALHA_PROCESSAMENTO").counter().count())
                .isEqualTo(1);
        assertThat(output.getAll()).contains("event=verification_error").doesNotContain("synthetic defect");
    }

    @Test
    void aCaseReclaimedPastTheMaximumAttemptsGoesToManualReviewWithoutAProviderCall() {
        String cnpj = "12345678000195";
        LinkCase linkCase = cases.save(TestCases.builder(workspace).cnpj(cnpj).responsavel(PARTNER).criadoEm(CRIADO_EM)
                .processamento(new Processamento(EstadoProcessamento.EM_ANDAMENTO, 3, CRIADO_EM, "crashed-worker",
                        NOW.minusSeconds(1), null))
                .build().linkCase());

        assertThat(worker(fake).process(linkCase.id())).isTrue();

        assertThat(fake.fullCheckCalls(cnpj)).isZero();
        verify(budgets, never()).bookD3(anyString());
        verify(budgets, never()).acquireProviderCall();
        assertThat(processamento(linkCase)).satisfies(p -> {
            assertThat(p.estado()).isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
            assertThat(p.motivoRevisao()).isEqualTo(MotivoRevisao.FALHA_PROCESSAMENTO);
        });
        assertThat(meters.get("kybr.verification.failed").tag("motivo", "FALHA_PROCESSAMENTO").counter().count())
                .isEqualTo(1);
    }

    @Test
    void aDeniedProviderRateGateReschedulesWithoutCallingTheProvider() {
        when(budgets.acquireProviderCall()).thenReturn(Optional.of(Duration.ofSeconds(12)));
        LinkCase linkCase = newCase("12345678000195", PARTNER);

        worker(fake).process(linkCase.id());

        assertThat(fake.fullCheckCalls("12345678000195")).isZero();
        assertThat(processamento(linkCase).proximaTentativa()).isEqualTo(NOW.plusSeconds(12));
        assertThat(processamento(linkCase).tentativas()).isZero();
    }

    static Stream<Arguments> spentBudgets() {
        return Stream.of(
                Arguments.of(Decision.exhausted(Budget.MONTHLY), MotivoRevisao.ORCAMENTO),
                Arguments.of(Decision.exhausted(Budget.D3_DAILY), MotivoRevisao.ORCAMENTO_DIARIO),
                Arguments.of(Decision.exhausted(Budget.D3_SLUG), MotivoRevisao.ORCAMENTO_DIARIO),
                Arguments.of(Decision.SPEND_OFF, MotivoRevisao.GASTO_DESLIGADO),
                Arguments.of(Decision.exhausted(Budget.NO_DATA), MotivoRevisao.LIMITE_SEM_DADOS));
    }

    @ParameterizedTest
    @MethodSource("spentBudgets")
    void aSpentBudgetDefersToManualReviewWithoutAProviderCall(Decision decision, MotivoRevisao motivo) {
        when(budgets.canSpendD3(workspace.slug())).thenReturn(decision);
        LinkCase linkCase = newCase("12345678000195", PARTNER);

        worker(fake).process(linkCase.id());

        assertThat(fake.fullCheckCalls("12345678000195")).isZero();
        assertThat(processamento(linkCase)).satisfies(p -> {
            assertThat(p.estado()).isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
            assertThat(p.motivoRevisao()).isEqualTo(motivo);
        });
        assertThat(cases.findById(linkCase.id()).orElseThrow().estado()).isEqualTo(EstadoCaso.processing);
        verify(budgets, never()).bookD3(anyString());
        assertThat(meters.get("kybr.verification.deferred").tag("motivo", motivo.name()).counter().count())
                .isEqualTo(1);
    }

    @Test
    void reachingTheNoDataCapCountsTheCapMetric() {
        when(budgets.canSpendD3(workspace.slug())).thenReturn(Decision.exhausted(Budget.NO_DATA));
        LinkCase linkCase = newCase("12345678000195", PARTNER);

        worker(fake).process(linkCase.id());

        assertThat(meters.get("kybr.verification.no_data_cap").tag("slug", workspace.slug()).counter().count())
                .isEqualTo(1);
    }

    @Test
    void aLostLeaseAbortsTheWriteAndNoVerificationIsKept() {
        String cnpj = "12345678000195";
        LinkCase linkCase = newCase(cnpj, "Maria Exemplo");
        CompanyDataProvider thief = new CompanyDataProvider() {
            @Override
            public CompanyDataResult<BasicCompany> basic(String ignored) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompanyDataResult<CompanyRecord> fullCheck(String ignored) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(linkCase.id())),
                        new Update().set(LinkCase.PROCESSAMENTO_LEASE_OWNER, "another-worker"), LinkCase.class);
                return new Found<>(FakeCompanyDataProvider.activeCompany());
            }
        };

        worker(thief).process(linkCase.id());

        assertThat(verifications.findByWorkspaceIdAndCaseId(workspace.id(), linkCase.id())).isEmpty();
        LinkCase untouched = cases.findById(linkCase.id()).orElseThrow();
        assertThat(untouched.estado()).isEqualTo(EstadoCaso.processing);
        assertThat(untouched.processamento().leaseOwner()).isEqualTo("another-worker");
        assertThat(sellers.findByWorkspaceIdAndCnpj(workspace.id(), cnpj)).isEmpty();
    }

    @Test
    void theVerdictWritesOnlyItsOwnFieldsOnAnExistingSeller() {
        String cnpj = "12345678000195";
        UUID sellerId = UUID.randomUUID();
        mongo.getCollection(Seller.COLLECTION).insertOne(new org.bson.Document("_id", sellerId)
                .append("workspace_id", workspace.id()).append("cnpj", cnpj).append("origem", "lote")
                .append("status", "em_verificacao").append("campo_alheio", "kept"));
        LinkCase linkCase = newCase(cnpj, PARTNER);

        worker(stub()).process(linkCase.id());

        org.bson.Document seller = mongo.getCollection(Seller.COLLECTION)
                .find(new org.bson.Document("workspace_id", workspace.id()).append("cnpj", cnpj)).first();
        assertThat(seller.get("_id")).isEqualTo(sellerId);
        assertThat(seller.getString("origem")).isEqualTo("lote");
        assertThat(seller.getString("campo_alheio")).isEqualTo("kept");
        assertThat(seller.getString("status")).isEqualTo("aprovado");
        assertThat(seller.get("ultima_verificacao_id"))
                .isEqualTo(cases.findById(linkCase.id()).orElseThrow().verificacaoId());
    }

    @Test
    void aTransientWriteConflictInTheVerdictTransactionIsRetriedWithoutAnotherProviderCall() {
        String cnpj = "52000012000110";
        fake.scriptFullCheck(cnpj, new Found<>(FakeCompanyDataProvider.activeCompany()));
        LinkCase linkCase = newCase(cnpj, "Maria Exemplo");
        java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger();
        SellerUpserts conflicting = new SellerUpserts(mongo) {
            @Override
            public void recordVerdict(UUID workspaceId, String sellerCnpj, String razaoSocial, StatusSeller status,
                    Instant verificadoEm, UUID verificacaoId) {
                if (conflicts.getAndIncrement() == 0) {
                    com.mongodb.MongoException conflict = new com.mongodb.MongoException(112, "synthetic conflict");
                    conflict.addLabel(com.mongodb.MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                    throw conflict;
                }
                super.recordVerdict(workspaceId, sellerCnpj, razaoSocial, status, verificadoEm, verificacaoId);
            }
        };

        worker(fake, new ChecksEvaluator(clock), conflicting).process(linkCase.id());

        assertThat(cases.findById(linkCase.id()).orElseThrow().estado()).isEqualTo(EstadoCaso.approved);
        assertThat(fake.fullCheckCalls(cnpj)).isEqualTo(1);
        assertThat(verifications.findByWorkspaceIdAndCaseId(workspace.id(), linkCase.id())).hasSize(1);
        assertThat(sellers.findByWorkspaceIdAndCnpj(workspace.id(), cnpj).orElseThrow().status())
                .isEqualTo(StatusSeller.aprovado);
    }

    @Test
    void processNextClaimsWhateverIsDue() {
        cases.deleteAll();
        LinkCase linkCase = newCase("12345678000195", PARTNER);
        VerificationWorker worker = worker(stub());

        assertThat(worker.processNext()).isTrue();
        assertThat(worker.processNext()).isFalse();

        assertThat(cases.findById(linkCase.id()).orElseThrow().estado()).isEqualTo(EstadoCaso.approved);
    }

    private VerificationWorker worker(CompanyDataProvider provider) {
        return worker(provider, new ChecksEvaluator(clock));
    }

    private VerificationWorker worker(CompanyDataProvider provider, ChecksEvaluator evaluator) {
        return worker(provider, evaluator, sellerUpserts);
    }

    private VerificationWorker worker(CompanyDataProvider provider, ChecksEvaluator evaluator,
            SellerUpserts upserts) {
        return new VerificationWorker(claims, workspaces, verifications, upserts, provider, budgets,
                evaluator, new VerdictEngine(properties, clock), transactionManager, 3,
                List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2)), Duration.ofSeconds(60),
                clock, meters);
    }

    private StubCompanyDataProvider stub() {
        return new StubCompanyDataProvider(properties, clock);
    }

    private LinkCase newCase(String cnpj, String responsavel) {
        return cases.save(TestCases.builder(workspace).cnpj(cnpj).responsavel(responsavel).criadoEm(CRIADO_EM)
                .processamento(Processamento.pendente(CRIADO_EM)).build().linkCase());
    }

    private Processamento processamento(LinkCase linkCase) {
        return cases.findById(linkCase.id()).orElseThrow().processamento();
    }
}
