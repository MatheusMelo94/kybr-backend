package com.matheusmelo.kybr.service.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.EstadoProcessamento;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.Processamento;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.ProcessingClaims;
import com.matheusmelo.kybr.repository.SellerUpserts;
import com.matheusmelo.kybr.repository.VerificationRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Found;
import com.matheusmelo.kybr.service.company.ProviderBudgets;
import com.matheusmelo.kybr.service.company.ProviderBudgets.Decision;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.link.FakeCompanyDataProvider;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class VerificationDispatchIT extends MongoIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String RESPONSAVEL = "Maria Exemplo";

    @Autowired
    private ProcessingClaims claims;
    @Autowired
    private LinkCaseRepository cases;
    @Autowired
    private WorkspaceRepository workspaces;
    @Autowired
    private VerificationRepository verifications;
    @Autowired
    private SellerUpserts sellers;
    @Autowired
    private MongoTransactionManager transactionManager;
    @Autowired
    private KybrProperties properties;

    private final Clock clock = Clock.systemUTC();
    private final FakeCompanyDataProvider fake = new FakeCompanyDataProvider();
    private final ProviderBudgets budgets = mock(ProviderBudgets.class);
    private final List<AutoCloseable> toStop = new ArrayList<>();
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        cases.deleteAll();
        workspace = workspaces.save(TestWorkspaces.builder().slug("loja-" + UUID.randomUUID().toString().substring(0, 8))
                .build());
        when(budgets.canSpendD3(anyString())).thenReturn(Decision.ALLOWED);
        when(budgets.acquireProviderCall()).thenReturn(Optional.empty());
    }

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable closeable : toStop) {
            closeable.close();
        }
    }

    @Test
    void theTestProfileWiresTheDispatcherButNoPoller(@Autowired ApplicationContext context) {
        assertThat(context.getBeansOfType(RecoveryPoller.class)).isEmpty();
        assertThat(context.getBean(VerificationDispatcher.class)).isInstanceOf(BoundedVerificationDispatcher.class);
    }

    @Test
    void aSubmittedCaseIsProcessed() {
        BoundedVerificationDispatcher dispatcher = dispatcher(2, 10);
        LinkCase linkCase = newCase(found());

        dispatcher.submit(linkCase.id());

        awaitUntil(() -> estado(linkCase) == EstadoCaso.approved);
    }

    @Test
    void aSubmissionInsideATransactionWaitsForTheCommitAndARollbackNeverDispatches() {
        // One worker runs submissions in order: a leaked rollback submission would run before the commit's.
        BoundedVerificationDispatcher dispatcher = dispatcher(1, 10);
        LinkCase rolledBack = newCase(found());
        LinkCase committed = newCase(found());
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(status -> {
            dispatcher.submit(rolledBack.id());
            status.setRollbackOnly();
        });
        transactions.executeWithoutResult(status -> dispatcher.submit(committed.id()));

        awaitUntil(() -> estado(committed) == EstadoCaso.approved);
        assertThat(processamento(rolledBack).estado()).isEqualTo(EstadoProcessamento.PENDENTE);
    }

    @Test
    void aFullQueueDropsTheSubmissionAndThePollerPicksTheCaseUpLater() {
        CountDownLatch release = new CountDownLatch(1);
        BoundedVerificationDispatcher dispatcher = dispatcher(1, 1);
        List<LinkCase> created = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String cnpj = found();
            fake.latch(cnpj, release);
            created.add(newCase(cnpj));
        }

        for (LinkCase linkCase : created) {
            assertThatCode(() -> dispatcher.submit(linkCase.id())).doesNotThrowAnyException();
        }
        RecoveryPoller poller = poller(dispatcher, Duration.ofMillis(100));
        poller.start();
        release.countDown();

        awaitUntil(() -> created.stream().allMatch(linkCase -> estado(linkCase) == EstadoCaso.approved));
    }

    @Test
    void aCaseWhoseWorkerCrashedIsReprocessedAfterItsLeaseExpires() {
        String cnpj = found();
        LinkCase crashed = cases.save(TestCases.builder(workspace).cnpj(cnpj).responsavel(RESPONSAVEL)
                .processamento(new Processamento(EstadoProcessamento.EM_ANDAMENTO, 1, Instant.now(), "gone",
                        Instant.now().minusSeconds(1), null))
                .build().linkCase());
        RecoveryPoller poller = poller(dispatcher(1, 10), Duration.ofMillis(100));

        poller.start();

        awaitUntil(() -> estado(crashed) == EstadoCaso.approved);
        assertThat(processamento(crashed).tentativas()).isEqualTo(2);
    }

    @Test
    void concurrencyNeverExceedsTheConfiguredWorkers() {
        BoundedVerificationDispatcher dispatcher = dispatcher(2, 20);
        List<LinkCase> created = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String cnpj = found();
            fake.delay(cnpj, Duration.ofMillis(150));
            created.add(newCase(cnpj));
        }

        created.forEach(linkCase -> dispatcher.submit(linkCase.id()));
        poller(dispatcher, Duration.ofMillis(50)).start();

        awaitUntil(() -> created.stream().allMatch(linkCase -> estado(linkCase) == EstadoCaso.approved));
        assertThat(fake.maxConcurrentCalls()).isBetween(1, 2);
    }

    @Test
    void afterShutdownNothingIsClaimed() {
        BoundedVerificationDispatcher dispatcher = dispatcher(1, 10);
        RecoveryPoller poller = poller(dispatcher, Duration.ofMillis(50));
        poller.start();
        poller.stop();
        dispatcher.shutdown();
        LinkCase linkCase = newCase(found());

        dispatcher.submit(linkCase.id());

        assertThat(dispatcher.freeCapacity()).as("nothing queued or running").isEqualTo(1);
        assertThat(poller.isRunning()).isFalse();
        assertThat(processamento(linkCase).estado()).isEqualTo(EstadoProcessamento.PENDENTE);
    }

    private BoundedVerificationDispatcher dispatcher(int workers, int queue) {
        VerificationWorker worker = new VerificationWorker(claims, workspaces, verifications, sellers, fake, budgets,
                new ChecksEvaluator(clock), new VerdictEngine(properties, clock), transactionManager, 3,
                List.of(Duration.ofMillis(100)), Duration.ofSeconds(60), clock, new SimpleMeterRegistry());
        BoundedVerificationDispatcher dispatcher = new BoundedVerificationDispatcher(worker, workers, queue);
        toStop.add(dispatcher::shutdown);
        return dispatcher;
    }

    private RecoveryPoller poller(BoundedVerificationDispatcher dispatcher, Duration interval) {
        RecoveryPoller poller = new RecoveryPoller(dispatcher, interval);
        toStop.add(poller::stop);
        return poller;
    }

    /** A fresh CNPJ-like key scripted as a found, active company. */
    private String found() {
        String cnpj = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 13).toUpperCase();
        fake.scriptFullCheck(cnpj, new Found<>(FakeCompanyDataProvider.activeCompany()));
        return cnpj;
    }

    private LinkCase newCase(String cnpj) {
        return cases.save(TestCases.builder(workspace).cnpj(cnpj).responsavel(RESPONSAVEL)
                .processamento(Processamento.pendente(Instant.now().minusSeconds(1))).build().linkCase());
    }

    private EstadoCaso estado(LinkCase linkCase) {
        return cases.findById(linkCase.id()).orElseThrow().estado();
    }

    private Processamento processamento(LinkCase linkCase) {
        return cases.findById(linkCase.id()).orElseThrow().processamento();
    }

    private static void awaitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + WAIT);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
