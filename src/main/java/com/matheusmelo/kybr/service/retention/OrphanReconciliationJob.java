package com.matheusmelo.kybr.service.retention;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.ResumoAuditoria;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.DocumentRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.jobs.BackgroundJob;
import com.matheusmelo.kybr.service.jobs.JobName;
import com.matheusmelo.kybr.service.storage.DocumentStorage;
import com.matheusmelo.kybr.service.storage.DocumentStorageException;
import com.matheusmelo.kybr.service.storage.StoredObject;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Deletes stored objects that nothing needs any more ({@code kybr.retention.orphan-interval},
 * weekly), so no copy of a seller document outlives its record: a crash between storing a file and
 * committing its record leaves one behind. Both areas are checked, each workspace's prefix listed
 * (paged by the storage):
 * <ul>
 *   <li>final: an object no {@code documents} record points to;
 *   <li>quarantine: an object whose file isn't still waiting for or under verification (no record,
 *       or a record already accepted or refused whose quarantined copy wasn't deleted).
 * </ul>
 * An object is deleted only once it is older than {@code kybr.retention.orphan-min-age} (24 h), since
 * a younger one may belong to an upload still committing. The quarantine bucket also expires
 * everything after 7 days on its own (a storage lifecycle rule), as the last safety net.
 *
 * <p>Every machine runs it on its schedule, without a lease: the deletions are idempotent, so a
 * second machine finds less or nothing, at worst counting and auditing the same object twice.
 *
 * <p>Counted in {@code kybr.retention.orphans_deleted}; each workspace with deletions gets one audit
 * {@code ORFAOS_REMOVIDOS} ({@code ator=sistema}) carrying the count only. No object key is ever
 * logged or audited.
 */
@Component
public class OrphanReconciliationJob implements BackgroundJob {

    private static final Logger log = LoggerFactory.getLogger(OrphanReconciliationJob.class);
    private static final List<EstadoDocumento> PENDING = List.of(EstadoDocumento.RECEBIDO, EstadoDocumento.VERIFICANDO);

    private final WorkspaceRepository workspaces;
    private final DocumentRepository documents;
    private final AuditEventRepository audits;
    private final DocumentStorage storage;
    private final Duration minAge;
    private final Clock clock;
    private final MeterRegistry meters;

    @Autowired
    public OrphanReconciliationJob(WorkspaceRepository workspaces, DocumentRepository documents,
            AuditEventRepository audits, DocumentStorage storage, KybrProperties properties, Clock clock,
            MeterRegistry meters) {
        this(workspaces, documents, audits, storage, properties.retention().orphanMinAge(), clock, meters);
    }

    OrphanReconciliationJob(WorkspaceRepository workspaces, DocumentRepository documents, AuditEventRepository audits,
            DocumentStorage storage, Duration minAge, Clock clock, MeterRegistry meters) {
        this.workspaces = workspaces;
        this.documents = documents;
        this.audits = audits;
        this.storage = storage;
        this.minAge = minAge;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public JobName name() {
        return JobName.ORPHANS;
    }

    @Override
    public void runOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant cutoff = now.minus(minAge);
        int total = 0;
        for (Workspace workspace : workspaces.findAll()) {
            int deleted = reconcile(workspace.id(), cutoff);
            if (deleted > 0) {
                audits.insert(new AuditEvent(UUID.randomUUID(), workspace.id(), TipoAuditoria.ORFAOS_REMOVIDOS,
                        Ator.sistema, null, now, null, true, null, null, ResumoAuditoria.ofRetencao(deleted, false)));
                total += deleted;
            }
        }
        log.info("event=orphans_reconciled removidos={}", total);
    }

    private int reconcile(UUID workspaceId, Instant cutoff) {
        return reconcile(workspaceId, cutoff, DocumentStorage.Area.FINAL, key -> !documents.existsByStorageKey(key))
                + reconcile(workspaceId, cutoff, DocumentStorage.Area.QUARANTINE,
                        key -> !documents.existsByQuarentenaKeyAndEstadoIn(key, PENDING));
    }

    private int reconcile(UUID workspaceId, Instant cutoff, DocumentStorage.Area area, Predicate<String> orphan) {
        List<String> orphans;
        try (Stream<StoredObject> objects = storage.list(area, DocumentStorage.prefix(workspaceId))) {
            orphans = objects.filter(object -> object.lastModified().isBefore(cutoff))
                    .map(StoredObject::key)
                    .filter(orphan)
                    .toList();
        }
        int deleted = 0;
        for (String key : orphans) {
            try {
                storage.delete(area, key);
                deleted++;
                meters.counter("kybr.retention.orphans_deleted").increment();
            } catch (DocumentStorageException e) {
                log.warn("event=orphan_delete_failed workspace_id={} error={}", workspaceId, e.getClass().getSimpleName());
            }
        }
        return deleted;
    }
}
