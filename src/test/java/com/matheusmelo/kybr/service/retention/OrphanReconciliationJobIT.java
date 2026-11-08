package com.matheusmelo.kybr.service.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.DocumentRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.jobs.JobName;
import com.matheusmelo.kybr.service.storage.DocumentStorage;
import com.matheusmelo.kybr.service.storage.InMemoryDocumentStorage;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.MovableClock;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class OrphanReconciliationJobIT extends MongoIntegrationTest {

    private static final Duration MIN_AGE = Duration.ofHours(24);

    @Autowired
    private WorkspaceRepository workspaces;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private AuditEventRepository audits;

    private final MovableClock clock = new MovableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    private final InMemoryDocumentStorage storage = new InMemoryDocumentStorage(clock);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void isTheOrphanJob() {
        assertThat(job().name()).isEqualTo(JobName.ORPHANS);
    }

    @Test
    void anOrphanIsKeptWithin24HoursAndDeletedAfterwardsWhileObjectsWithRecordsStay(CapturedOutput output) {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().build());
        UUID caseId = UUID.randomUUID();
        String withRecord = store(workspace, caseId, true);
        String orphan = store(workspace, caseId, false);
        clock.advance(MIN_AGE.minusSeconds(1));

        job().runOnce();

        assertThat(storage.exists(DocumentStorage.Area.FINAL, orphan)).isTrue();
        assertThat(storage.exists(DocumentStorage.Area.FINAL, withRecord)).isTrue();

        clock.advance(Duration.ofSeconds(2));
        String young = store(workspace, caseId, false);
        job().runOnce();

        assertThat(storage.exists(DocumentStorage.Area.FINAL, orphan)).isFalse();
        assertThat(storage.exists(DocumentStorage.Area.FINAL, young)).isTrue();
        assertThat(storage.exists(DocumentStorage.Area.FINAL, withRecord)).isTrue();
        assertThat(meters.counter("kybr.retention.orphans_deleted").count()).isEqualTo(1);
        assertThat(orphanAudits(workspace)).singleElement().satisfies(audit -> {
            assertThat(audit.ator()).isEqualTo(Ator.sistema);
            assertThat(audit.caseId()).isNull();
            assertThat(audit.resumo().documentos()).isEqualTo(1);
        });
        assertThat(output.getAll()).doesNotContain(orphan).doesNotContain(caseId.toString());
    }

    @Test
    void everyWorkspaceIsCheckedAndAWorkspaceWithoutOrphansGetsNoAudit() {
        Workspace first = workspaces.save(TestWorkspaces.builder().build());
        Workspace second = workspaces.save(TestWorkspaces.builder().build());
        Workspace clean = workspaces.save(TestWorkspaces.builder().build());
        store(first, UUID.randomUUID(), false);
        store(second, UUID.randomUUID(), false);
        store(second, UUID.randomUUID(), false);
        store(clean, UUID.randomUUID(), true);
        clock.advance(MIN_AGE.plusSeconds(1));

        job().runOnce();

        assertThat(storage.list(DocumentStorage.Area.FINAL, DocumentStorage.prefix(first.id()))).isEmpty();
        assertThat(storage.list(DocumentStorage.Area.FINAL, DocumentStorage.prefix(second.id()))).isEmpty();
        assertThat(storage.list(DocumentStorage.Area.FINAL, DocumentStorage.prefix(clean.id()))).hasSize(1);
        assertThat(orphanAudits(second)).singleElement().extracting(audit -> audit.resumo().documentos()).isEqualTo(2);
        assertThat(orphanAudits(clean)).isEmpty();
    }

    @Test
    void quarantinedObjectsAreKeptOnlyWhileTheirFileIsStillBeingVerified() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().build());
        UUID caseId = UUID.randomUUID();
        String waiting = quarantine(workspace, caseId, EstadoDocumento.RECEBIDO);
        String claimed = quarantine(workspace, caseId, EstadoDocumento.VERIFICANDO);
        String acceptedLeftover = quarantine(workspace, caseId, EstadoDocumento.ACEITO);
        String noRecord = quarantine(workspace, caseId, null);
        clock.advance(MIN_AGE.plusSeconds(1));
        String young = quarantine(workspace, caseId, null);

        job().runOnce();

        assertThat(storage.exists(DocumentStorage.Area.QUARANTINE, waiting)).isTrue();
        assertThat(storage.exists(DocumentStorage.Area.QUARANTINE, claimed)).isTrue();
        assertThat(storage.exists(DocumentStorage.Area.QUARANTINE, young)).isTrue();
        assertThat(storage.exists(DocumentStorage.Area.QUARANTINE, acceptedLeftover)).isFalse();
        assertThat(storage.exists(DocumentStorage.Area.QUARANTINE, noRecord)).isFalse();
        assertThat(orphanAudits(workspace)).singleElement().extracting(audit -> audit.resumo().documentos()).isEqualTo(2);
    }

    @Test
    void anAbandonedScanAttemptsFinalKeyIsAnOrphanAndTheCommittedKeyStays() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().build());
        UUID caseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        String committed = DocumentStorage.attemptKey(workspace.id(), caseId, documentId, "vencedora");
        String abandoned = DocumentStorage.attemptKey(workspace.id(), caseId, documentId, "perdedora");
        for (String key : List.of(committed, abandoned)) {
            storage.put(DocumentStorage.Area.FINAL, key, new ByteArrayInputStream(new byte[] {1}), 1, "application/pdf",
                    Map.of());
        }
        documents.save(new Document(documentId, workspace.id(), caseId, null, DocumentoTipo.CARTAO_CNPJ,
                FormatoEnvio.pdf, 1, committed, "application/pdf", 1, 1,
                documentId.toString().replace("-", "") + "2".repeat(32), "d".repeat(64), clock.instant()));
        clock.advance(MIN_AGE.plusSeconds(1));

        job().runOnce();

        assertThat(storage.exists(DocumentStorage.Area.FINAL, committed)).isTrue();
        assertThat(storage.exists(DocumentStorage.Area.FINAL, abandoned)).isFalse();
    }

    private String quarantine(Workspace workspace, UUID caseId, EstadoDocumento estado) {
        UUID documentId = UUID.randomUUID();
        String key = DocumentStorage.key(workspace.id(), caseId, documentId);
        storage.put(DocumentStorage.Area.QUARANTINE, key, new ByteArrayInputStream(new byte[] {1}), 1, "application/pdf",
                Map.of());
        if (estado != null) {
            Document received = Document.recebido(documentId, workspace.id(), caseId, null, DocumentoTipo.CARTAO_CNPJ,
                    FormatoEnvio.pdf, 1, "application/pdf", 1, documentId.toString().replace("-", "") + "1".repeat(32),
                    clock.instant(), key, UUID.randomUUID());
            documents.save(new Document(received.id(), received.workspaceId(), received.caseId(), null, received.tipo(),
                    received.formato(), 1, estado == EstadoDocumento.ACEITO ? "f/" + key : null, received.contentType(),
                    1, null, received.sha256Original(), null, received.criadoEm(), estado, null, key, received.envioId(),
                    true, 1, received.proximaTentativa(), null, null));
        }
        return key;
    }

    private OrphanReconciliationJob job() {
        return new OrphanReconciliationJob(workspaces, documents, audits, storage, MIN_AGE, clock, meters);
    }

    private String store(Workspace workspace, UUID caseId, boolean withRecord) {
        UUID documentId = UUID.randomUUID();
        String key = DocumentStorage.key(workspace.id(), caseId, documentId);
        storage.put(DocumentStorage.Area.FINAL, key, new ByteArrayInputStream(new byte[] {1}), 1, "application/pdf", Map.of());
        if (withRecord) {
            documents.save(new Document(documentId, workspace.id(), caseId, null, DocumentoTipo.CARTAO_CNPJ,
                    FormatoEnvio.pdf, 1, key, "application/pdf", 1, 1, documentId.toString().replace("-", "") + "0".repeat(32),
                    "d".repeat(64), clock.instant()));
        }
        return key;
    }

    private List<AuditEvent> orphanAudits(Workspace workspace) {
        return audits.findAll().stream()
                .filter(event -> event.tipo() == TipoAuditoria.ORFAOS_REMOVIDOS && workspace.id().equals(event.workspaceId()))
                .toList();
    }
}
