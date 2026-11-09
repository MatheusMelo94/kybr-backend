package com.matheusmelo.kybr.service.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.DocumentRequest;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.Contato;
import com.matheusmelo.kybr.model.link.EstadoRetencao;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.link.TipoOutbox;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.DocumentRepository;
import com.matheusmelo.kybr.repository.DocumentRequestRepository;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.OutboxEventRepository;
import com.matheusmelo.kybr.repository.RetentionClaims;
import com.matheusmelo.kybr.service.jobs.JobName;
import com.matheusmelo.kybr.service.storage.DocumentStorage;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.MovableClock;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.upload.ScriptedStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;

class RetentionJobIT extends MongoIntegrationTest {

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private RetentionClaims claims;
    @Autowired
    private LinkCaseRepository cases;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private DocumentRequestRepository requests;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private AuditEventRepository audits;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MongoTemplate mongo;

    private final MovableClock clock = new MovableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    private final ScriptedStorage storage = new ScriptedStorage(clock);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Workspace workspace = TestWorkspaces.builder().build();
    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), LinkCase.class);
        mongo.remove(new Query(), Document.class);
        mongo.remove(new Query(), DocumentRequest.class);
        mongo.remove(new Query(), OutboxEvent.class);
    }

    @Test
    void isTheRetentionJob() {
        assertThat(job(audits).name()).isEqualTo(JobName.RETENTION);
    }

    @Test
    void aDueCaseLosesItsDocumentsContactAndNotificationsAndItsTokenIsRevoked() {
        LinkCase due = dueCase(clock.instant().minusSeconds(1), 3);
        UUID requestId = openRequest(due.id());
        AuditEvent earlier = audit(due, TipoAuditoria.CONTATO_ALTERADO, Ator.vendedor);

        job(audits).runOnce();

        assertThat(storage.keys()).isEmpty();
        assertThat(documents.findByCaseId(due.id())).isEmpty();
        assertThat(outbox.findAll()).noneMatch(event -> event.caseId().equals(due.id()));
        LinkCase stored = cases.findById(due.id()).orElseThrow();
        assertThat(stored.contato()).isNull();
        assertThat(stored.tokenRevogadoEm()).isEqualTo(clock.instant());
        assertThat(stored.retencao().estado()).isEqualTo(EstadoRetencao.CONCLUIDA);
        assertThat(stored.retencao().executadaEm()).isEqualTo(clock.instant());
        assertThat(stored.cnpj()).isEqualTo(due.cnpj());
        assertThat(requests.findById(requestId).orElseThrow().encerradoEm()).isEqualTo(clock.instant());
        assertThat(retentionAudits(due)).singleElement().satisfies(audit -> {
            assertThat(audit.ator()).isEqualTo(Ator.sistema);
            assertThat(audit.workspaceId()).isEqualTo(workspace.id());
            assertThat(audit.resumo().documentos()).isEqualTo(3);
            assertThat(audit.resumo().contatoRemovido()).isTrue();
        });
        assertThat(audits.findById(earlier.id())).isPresent();
        assertThat(meters.counter("kybr.retention.processed").count()).isEqualTo(1);
    }

    @Test
    void aCaseNotYetDueIsUntouched() {
        LinkCase notYet = dueCase(clock.instant().plusSeconds(1), 1);

        job(audits).runOnce();

        assertThat(cases.findById(notYet.id()).orElseThrow()).isEqualTo(notYet);
        assertThat(storage.keys()).hasSize(1);
        assertThat(documents.findByCaseId(notYet.id())).hasSize(1);
    }

    @Test
    void aFailureAfterTheStorageDeletesIsCompletedByTheNextRunWithOneAudit() {
        LinkCase due = dueCase(clock.instant().minusSeconds(1), 3);
        AtomicBoolean failOnce = new AtomicBoolean(true);

        job(failingFirstInsert(failOnce)).runOnce();

        assertThat(storage.keys()).isEmpty();
        assertThat(documents.findByCaseId(due.id())).hasSize(3);
        LinkCase interrupted = cases.findById(due.id()).orElseThrow();
        assertThat(interrupted.retencao().estado()).isEqualTo(EstadoRetencao.EM_ANDAMENTO);
        assertThat(interrupted.tokenRevogadoEm()).isNotNull();
        assertThat(meters.counter("kybr.retention.failed").count()).isEqualTo(1);

        clock.advance(LEASE.plusSeconds(1));
        job(audits).runOnce();

        assertThat(documents.findByCaseId(due.id())).isEmpty();
        assertThat(cases.findById(due.id()).orElseThrow().retencao().estado()).isEqualTo(EstadoRetencao.CONCLUIDA);
        assertThat(retentionAudits(due)).hasSize(1);
    }

    @Test
    void aStorageFailureLeavesTheRecordsForTheNextRun() {
        LinkCase due = dueCase(clock.instant().minusSeconds(1), 2);
        storage.failDeleteOnCall(2);

        job(audits).runOnce();

        assertThat(documents.findByCaseId(due.id())).hasSize(2);
        assertThat(cases.findById(due.id()).orElseThrow().contato()).isNotNull();
        assertThat(retentionAudits(due)).isEmpty();
        assertThat(meters.counter("kybr.retention.failed").count()).isEqualTo(1);

        clock.advance(LEASE.plusSeconds(1));
        job(audits).runOnce();

        assertThat(storage.keys()).isEmpty();
        assertThat(retentionAudits(due)).hasSize(1);
    }

    @Test
    void twoJobsProcessEachCaseOnce() throws Exception {
        LinkCase first = dueCase(clock.instant().minusSeconds(2), 2);
        LinkCase second = dueCase(clock.instant().minusSeconds(1), 1);
        RetentionJob a = job(audits);
        RetentionJob b = job(audits);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> one = pool.submit(() -> {
                start.await();
                a.runOnce();
                return null;
            });
            Future<?> two = pool.submit(() -> {
                start.await();
                b.runOnce();
                return null;
            });
            start.countDown();
            one.get();
            two.get();
        }

        assertThat(retentionAudits(first)).hasSize(1);
        assertThat(retentionAudits(second)).hasSize(1);
        assertThat(storage.keys()).isEmpty();
    }

    private RetentionJob job(AuditEventRepository auditRepository) {
        return new RetentionJob(claims, documents, outbox, auditRepository, storage, transactionManager, LEASE, clock,
                meters);
    }

    private LinkCase dueCase(Instant retencaoAte, int documentCount) {
        UUID caseId = UUID.randomUUID();
        LinkCase linkCase = cases.save(TestCases.builder(workspace).id(caseId).retencaoAte(retencaoAte)
                .criadoEm(clock.instant().minus(Duration.ofDays(400)))
                .contato(new Contato(new byte[] {1, 2, 3}, "m•••@example.test", clock.instant()))
                .build().linkCase());
        for (int page = 1; page <= documentCount; page++) {
            UUID documentId = UUID.randomUUID();
            String key = DocumentStorage.key(workspace.id(), caseId, documentId);
            storage.put(DocumentStorage.Area.FINAL, key, new ByteArrayInputStream(new byte[] {(byte) page}), 1, "application/pdf", Map.of());
            keys.add(key);
            documents.save(new Document(documentId, workspace.id(), caseId, null, DocumentoTipo.CARTAO_CNPJ,
                    FormatoEnvio.fotos, page, key, "image/jpeg", 1, 1, "a".repeat(63) + page, "b".repeat(63) + page,
                    clock.instant()));
        }
        UUID eventId = UUID.randomUUID();
        outbox.save(new OutboxEvent(eventId, TipoOutbox.CONTATO_ALTERADO, caseId, new byte[] {9}, clock.instant(),
                clock.instant().plus(Duration.ofDays(30))));
        return linkCase;
    }

    private UUID openRequest(UUID caseId) {
        return requests.save(new DocumentRequest(UUID.randomUUID(), workspace.id(), caseId,
                TestCases.hash(TestCases.newToken()), List.of(DocumentoTipo.CARTAO_CNPJ), clock.instant(),
                clock.instant().plus(Duration.ofDays(30)), null, null)).id();
    }

    private AuditEvent audit(LinkCase linkCase, TipoAuditoria tipo, Ator ator) {
        return audits.insert(new AuditEvent(UUID.randomUUID(), linkCase.workspaceId(), tipo, ator, linkCase.id(),
                clock.instant(), null, true, null, null, null));
    }

    private List<AuditEvent> retentionAudits(LinkCase linkCase) {
        return audits.findAll().stream()
                .filter(event -> event.tipo() == TipoAuditoria.RETENCAO_EXECUTADA && linkCase.id().equals(event.caseId()))
                .toList();
    }

    /** An audit repository whose first insert fails, as a database fault inside the transaction would. */
    private AuditEventRepository failingFirstInsert(AtomicBoolean failOnce) {
        return (AuditEventRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {AuditEventRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("insert") && failOnce.getAndSet(false)) {
                        throw new IllegalStateException("simulated database fault");
                    }
                    try {
                        return method.invoke(audits, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
