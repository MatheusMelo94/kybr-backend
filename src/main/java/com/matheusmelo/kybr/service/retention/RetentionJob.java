package com.matheusmelo.kybr.service.retention;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.ResumoAuditoria;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.DocumentRepository;
import com.matheusmelo.kybr.repository.OutboxEventRepository;
import com.matheusmelo.kybr.repository.RetentionClaims;
import com.matheusmelo.kybr.service.TransactionRetry;
import com.matheusmelo.kybr.service.jobs.BackgroundJob;
import com.matheusmelo.kybr.service.jobs.JobName;
import com.matheusmelo.kybr.service.storage.DocumentStorage;
import com.matheusmelo.kybr.service.storage.StoredObject;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes what a case may keep only for a while ({@code kybr.retention.interval}, hourly): its
 * stored documents and the seller's contact e-mail, once {@code retencao_ate} has passed. The case,
 * its verification, the audit trail and the seller stay.
 *
 * <p>Per case, claimed one at a time with a lease:
 * <ol>
 *   <li>the claim revokes the case token in the same atomic update ({@link RetentionClaims});
 *   <li>the case's open document requests are closed;
 *   <li>every object under the case's prefix is deleted from the final area and from the
 *       quarantine, whatever the files' state; a scan job still working on one of them finds the
 *       token revoked and accepts nothing;
 *   <li>one transaction deletes the {@code documents} rows and the case's outbox events, clears the
 *       contact, marks the run concluded (fenced on the lease) and writes the audit
 *       {@code RETENCAO_EXECUTADA} (counts only) with {@code ator=sistema}.
 * </ol>
 * A failure leaves the case claimed with its token revoked; once the lease expires, the next run
 * reads the list again and completes it. Counted in {@code kybr.retention.processed} and
 * {@code kybr.retention.failed}; failures are logged by case id and error type only.
 */
@Component
public class RetentionJob implements BackgroundJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    /** The most cases one run handles, so a run always ends. */
    private static final int MAX_PER_RUN = 50;

    private final RetentionClaims claims;
    private final DocumentRepository documents;
    private final OutboxEventRepository outbox;
    private final AuditEventRepository audits;
    private final DocumentStorage storage;
    private final TransactionTemplate transactions;
    private final Duration lease;
    private final Clock clock;
    private final MeterRegistry meters;

    @Autowired
    public RetentionJob(RetentionClaims claims, DocumentRepository documents, OutboxEventRepository outbox,
            AuditEventRepository audits, DocumentStorage storage, PlatformTransactionManager transactionManager,
            KybrProperties properties, Clock clock, MeterRegistry meters) {
        this(claims, documents, outbox, audits, storage, transactionManager, properties.retention().lease(), clock,
                meters);
    }

    RetentionJob(RetentionClaims claims, DocumentRepository documents, OutboxEventRepository outbox,
            AuditEventRepository audits, DocumentStorage storage, PlatformTransactionManager transactionManager,
            Duration lease, Clock clock, MeterRegistry meters) {
        this.claims = claims;
        this.documents = documents;
        this.outbox = outbox;
        this.audits = audits;
        this.storage = storage;
        this.transactions = new TransactionTemplate(transactionManager);
        this.lease = lease;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public JobName name() {
        return JobName.RETENTION;
    }

    @Override
    public void runOnce() {
        String owner = UUID.randomUUID().toString();
        for (int handled = 0; handled < MAX_PER_RUN; handled++) {
            Optional<LinkCase> claimed = claims.claimNext(now(), owner, lease);
            if (claimed.isEmpty()) {
                return;
            }
            try {
                purge(claimed.get(), owner);
                meters.counter("kybr.retention.processed").increment();
            } catch (RuntimeException e) {
                meters.counter("kybr.retention.failed").increment();
                log.error("event=retention_failed case_id={} error={}", claimed.get().id(), e.getClass().getSimpleName());
            }
        }
    }

    private void purge(LinkCase linkCase, String owner) {
        claims.closeDocumentRequests(linkCase.id(), now());
        // everything under the case's prefix in both areas, whatever the files' state: received files are
        // only in quarantine, and an abandoned scan attempt may have left its own final key behind
        String prefix = DocumentStorage.casePrefix(linkCase.workspaceId(), linkCase.id());
        for (DocumentStorage.Area area : DocumentStorage.Area.values()) {
            List<String> keys;
            try (Stream<StoredObject> objects = storage.list(area, prefix)) {
                keys = objects.map(StoredObject::key).toList();
            }
            for (String key : keys) {
                storage.delete(area, key);
            }
        }
        boolean hadContact = linkCase.contato() != null;
        TransactionRetry.DEFAULT.execute(() -> transactions.execute(status -> {
            Instant now = now();
            long deleted = documents.deleteByCaseId(linkCase.id());
            outbox.deleteByCaseId(linkCase.id());
            if (!claims.conclude(linkCase.id(), owner, now)) {
                throw new IllegalStateException("the retention lease was lost");
            }
            audits.insert(new AuditEvent(UUID.randomUUID(), linkCase.workspaceId(), TipoAuditoria.RETENCAO_EXECUTADA,
                    Ator.sistema, linkCase.id(), now, null, true, null, null,
                    ResumoAuditoria.ofRetencao(Math.toIntExact(deleted), hadContact)));
            return null;
        }));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
