package com.matheusmelo.kybr.service.upload;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.RetryLaterException;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.DocumentItemState;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.ResumoAuditoria;
import com.matheusmelo.kybr.model.link.ResumoDocumento;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.repository.CaseDocumentWrites;
import com.matheusmelo.kybr.service.TransactionRetry;
import com.matheusmelo.kybr.service.storage.DocumentStorage;
import com.matheusmelo.kybr.service.storage.DocumentStorageException;
import com.matheusmelo.kybr.service.upload.scan.ScanDispatcher;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The upload after its reservation: quarantine, then record. Nothing is scanned here; the scan job
 * verifies each file later ({@link ScanDispatcher}).
 *
 * <ol>
 *   <li>Put each new file in the quarantine under the key of its future record (a storage failure
 *       is 503 with {@code Retry-After: 30}).
 *   <li>One transaction, re-run on transient errors: one {@code RECEBIDO} record per file (each a
 *       due scan job), the type's D4 item ({@code situacao_envio=verificando}) and the
 *       {@code DOCUMENTO_ENVIADO} audit with every file's hash and size and the socket IP. The case
 *       write is conditional: it lands only while the case token is live, retention hasn't started
 *       and the case isn't decided; otherwise 404 {@code CASO_NAO_ENCONTRADO}.
 *   <li>Nudge the scan job.
 * </ol>
 *
 * Any failure deletes the quarantined objects; the caller's {@link PreparedUpload} releases the
 * reservation. A concurrent identical upload that recorded the same file first (the unique
 * original-bytes hash) makes this one a retry: it compensates and answers the type's current state
 * (202).
 */
@Component
public class UploadCommit {

    static final long RETRY_AFTER_SECONDS = 30;
    private static final Logger log = LoggerFactory.getLogger(UploadCommit.class);

    /** The upload's commit can't happen: the case token was revoked, retention started, or it was decided. */
    private static final class CaseGone extends RuntimeException {
        CaseGone() {
            super(null, null, false, false);
        }
    }

    /** One quarantined file and its future record's id. */
    private record Quarantined(PreparedUpload.IncomingFile file, UUID id, String key) {
    }

    private final DocumentStorage storage;
    private final CaseDocumentWrites cases;
    private final MongoTemplate mongo;
    private final TransactionTemplate transactions;
    private final ScanDispatcher scans;
    private final Clock clock;

    public UploadCommit(DocumentStorage storage, CaseDocumentWrites cases, MongoTemplate mongo,
            PlatformTransactionManager transactionManager, ScanDispatcher scans, Clock clock) {
        this.storage = storage;
        this.cases = cases;
        this.mongo = mongo;
        this.transactions = new TransactionTemplate(transactionManager);
        this.scans = scans;
        this.clock = clock;
    }

    /**
     * Quarantines and records the prepared upload's new files.
     *
     * @return the type's D4 state after the commit
     * @throws ApiException 503 or 404 as described above; nothing is kept
     */
    public DocumentItemState commit(PreparedUpload upload, ClientIp clientIp) {
        List<Quarantined> quarantined = new ArrayList<>();
        DocumentItemState state;
        try {
            for (PreparedUpload.IncomingFile file : upload.files()) {
                UUID id = UUID.randomUUID();
                String key = DocumentStorage.key(upload.capability().workspaceId(), upload.capability().caseId(), id);
                try (InputStream content = file.part().open()) {
                    storage.put(DocumentStorage.Area.QUARANTINE, key, content, file.size(), file.kind().contentType(),
                            Map.of());
                }
                quarantined.add(new Quarantined(file, id, key));
            }
            state = TransactionRetry.DEFAULT.execute(() -> transactions.execute(status ->
                    record(upload, quarantined, clientIp)));
            upload.committed();
        } catch (DocumentStorageException e) {
            deleteAll(quarantined);
            throw new RetryLaterException(RETRY_AFTER_SECONDS, e);
        } catch (DuplicateKeyException e) {
            // an identical upload recorded the same file first: this one gives its copy back (the caller
            // releases the reservation) and answers the type's current state, like any retry
            deleteAll(quarantined);
            log.info("event=upload_concurrent_retry");
            return DocumentItemState.of(cases.files(upload.capability().caseId(), upload.tipo())).orElseThrow();
        } catch (CaseGone e) {
            deleteAll(quarantined);
            throw new ApiException(ErrorCode.CASO_NAO_ENCONTRADO);
        } catch (IOException e) {
            deleteAll(quarantined);
            throw new UncheckedIOException("couldn't read an uploaded file", e);
        } catch (RuntimeException | Error e) {
            deleteAll(quarantined);
            throw e;
        }
        nudge(); // committed: from here nothing deletes the quarantined copies
        return state;
    }

    /**
     * Starts the scan job; a failure (e.g. the machine is shutting down) changes nothing, since the
     * files are committed and the poller picks them up. Outside the compensating {@code try}, so a
     * committed file's quarantined copy is never deleted.
     */
    private void nudge() {
        try {
            scans.nudge();
        } catch (RuntimeException e) {
            log.warn("event=scan_nudge_failed error={}", e.getClass().getSimpleName());
        }
    }

    private DocumentItemState record(PreparedUpload upload, List<Quarantined> quarantined, ClientIp clientIp) {
        UUID caseId = upload.capability().caseId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        UUID envioId = UUID.randomUUID();
        int pagina = (int) cases.files(caseId, upload.tipo()).stream()
                .filter(file -> file.estadoOuAceito() != EstadoDocumento.RECUSADO).count();
        List<ResumoDocumento.Arquivo> audited = new ArrayList<>();
        for (Quarantined file : quarantined) {
            pagina = upload.formato() == FormatoEnvio.pdf ? 1 : pagina + 1;
            mongo.insert(Document.recebido(file.id(), upload.capability().workspaceId(), caseId,
                    upload.capability().documentRequestId(), upload.tipo(), upload.formato(), pagina,
                    file.file().kind().contentType(), file.file().size(), file.file().sha256Original(), now, file.key(),
                    envioId));
            audited.add(new ResumoDocumento.Arquivo(file.file().sha256Original(), null, file.file().size(), null));
        }
        DocumentItemState state = DocumentItemState.of(cases.files(caseId, upload.tipo())).orElseThrow();
        if (!cases.recordItem(caseId, upload.tipo(), state, now, true)) {
            throw new CaseGone();
        }
        boolean known = clientIp.isPerClientIdentity();
        mongo.insert(new AuditEvent(UUID.randomUUID(), upload.capability().workspaceId(), TipoAuditoria.DOCUMENTO_ENVIADO,
                Ator.vendedor, caseId, now, known ? clientIp.ip() : null, !known, null, null,
                ResumoAuditoria.ofDocumento(new ResumoDocumento(upload.tipo(), audited, null))));
        return state;
    }

    private void deleteAll(List<Quarantined> quarantined) {
        for (Quarantined file : quarantined) {
            try {
                storage.delete(DocumentStorage.Area.QUARANTINE, file.key());
            } catch (RuntimeException e) {
                // the orphan check removes it later
                log.warn("event=storage_delete_deferred area=QUARANTINE error={}", e.getClass().getSimpleName());
            }
        }
    }
}
