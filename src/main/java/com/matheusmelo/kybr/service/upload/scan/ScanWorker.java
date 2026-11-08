package com.matheusmelo.kybr.service.upload.scan;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.DocumentItemState;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.document.MotivoRecusa;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.CaseEvent;
import com.matheusmelo.kybr.model.link.ResumoAuditoria;
import com.matheusmelo.kybr.model.link.ResumoDocumento;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.link.TipoEventoCaso;
import com.matheusmelo.kybr.repository.CaseDocumentWrites;
import com.matheusmelo.kybr.repository.ScanClaims;
import com.matheusmelo.kybr.service.TransactionRetry;
import com.matheusmelo.kybr.service.image.ImageHeaderCheck;
import com.matheusmelo.kybr.service.image.PhotoSanitizer;
import com.matheusmelo.kybr.service.image.SanitizeResult;
import com.matheusmelo.kybr.service.scan.MalwareScanner;
import com.matheusmelo.kybr.service.scan.ScanVerdict;
import com.matheusmelo.kybr.service.storage.DocumentStorage;
import com.matheusmelo.kybr.service.storage.DocumentStorageException;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies one claimed file, once the session's scanner is awake and fresh. Each step is final:
 *
 * <ol>
 *   <li>read the quarantined object once into a temp file and check it is exactly the file that was
 *       sent (size and SHA-256); a mismatch refuses it generically ({@code VERIFICACAO_FALHOU}). The
 *       scan, the sanitizer and the PDF copy all read this one verified copy;
 *   <li>scan it: only an exact clean verdict passes. A detection refuses the file
 *       ({@code CONTEUDO_NAO_PERMITIDO}, never called malware; the audit keeps the signature name);
 *       anything else is retried;
 *   <li>photos: re-encoded by the photo sanitizer. A photo it can't decode is damaged or unreadable:
 *       refused at once as {@code ARQUIVO_INVALIDO}, never retried. An unavailable sanitizer is
 *       retried. PDFs are stored as received;
 *   <li>store the final bytes under this attempt's own key ({@code document key/lease owner}), so
 *       two workers racing on one document never share an object;
 *   <li>one transaction, re-run on transient errors and fenced on this worker's lease: the row
 *       {@code ACEITO}, the case's D4 item (a conditional write that lands only while the case token
 *       is live and retention hasn't started), the {@code documento_recebido} event and the
 *       {@code DOCUMENTO_ACEITO} audit;
 *   <li>delete the quarantined object (the orphan check covers a crash just before).
 * </ol>
 *
 * A retry goes back to {@code RECEBIDO} after {@code kybr.scan.backoff}; the file's
 * {@code kybr.scan.max-attempts}th failure refuses it ({@code VERIFICACAO_FALHOU}). A refusal gives
 * the file's share of the case's limits back, deletes the quarantined object and is audited
 * {@code DOCUMENTO_RECUSADO}. A lost lease or a gone case at commit accepts nothing and deletes only
 * the final object this attempt wrote; any other unrecorded attempt key is left to the orphan
 * check. Outcomes are counted on {@value #RESULT_METRIC}.
 */
@Component
public class ScanWorker {

    public static final String RESULT_METRIC = "kybr.scan.result";
    public static final String MALWARE_METRIC = "kybr.upload.malware";
    public static final String REENCODE_METRIC = "kybr.image.reencode";
    private static final Logger log = LoggerFactory.getLogger(ScanWorker.class);

    /** The lease moved to another worker (or the row left {@code VERIFICANDO}): stop writing. */
    private static final class LeaseLost extends RuntimeException {
        LeaseLost() {
            super(null, null, false, false);
        }
    }

    /** The case token was revoked or retention started: nothing may be accepted onto it. */
    private static final class CaseGone extends RuntimeException {
        CaseGone() {
            super(null, null, false, false);
        }
    }

    /** What to store: the re-encoded bytes (null for a PDF, stored from the verified copy) and their hash. */
    private record Verified(byte[] sanitized, String sha256, long size) {
    }

    private final ScanClaims claims;
    private final CaseDocumentWrites cases;
    private final DocumentStorage storage;
    private final MalwareScanner scanner;
    private final PhotoSanitizer sanitizer;
    private final MongoTemplate mongo;
    private final TransactionTemplate transactions;
    private final KybrProperties.Scan settings;
    private final Path tempDir;
    private final Clock clock;
    private final MeterRegistry meters;

    public ScanWorker(ScanClaims claims, CaseDocumentWrites cases, DocumentStorage storage, MalwareScanner scanner,
            PhotoSanitizer sanitizer, MongoTemplate mongo, PlatformTransactionManager transactionManager,
            KybrProperties properties, Clock clock, MeterRegistry meters) {
        this.claims = claims;
        this.cases = cases;
        this.storage = storage;
        this.scanner = scanner;
        this.sanitizer = sanitizer;
        this.mongo = mongo;
        this.transactions = new TransactionTemplate(transactionManager);
        this.settings = properties.scan();
        this.tempDir = properties.upload().tempDir();
        this.clock = clock;
        this.meters = meters;
    }

    /** Verifies {@code file}, claimed by {@code owner}; never throws for a file or dependency problem. */
    public void process(Document file, String owner) {
        Path copy = null;
        try {
            copy = verifiedCopy(file, owner);
            if (copy == null) {
                return; // refused: the object isn't the file that was sent
            }
            ScanVerdict verdict;
            try (InputStream content = Files.newInputStream(copy)) {
                verdict = scanner.scan(content, file.bytesOriginal());
            }
            switch (verdict) {
                case ScanVerdict.Infected infected -> {
                    meters.counter(MALWARE_METRIC).increment();
                    log.warn("event=document_refused motivo=deteccao document_id={} signature={}", file.id(),
                            infected.signature());
                    refuse(file, owner, MotivoRecusa.CONTEUDO_NAO_PERMITIDO, infected.signature(), "recusado_conteudo");
                    return;
                }
                case ScanVerdict.Unavailable unavailable -> {
                    retryOrRefuse(file, owner);
                    return;
                }
                case ScanVerdict.Clean clean -> {
                }
            }
            Verified verified;
            if (file.formato() == FormatoEnvio.pdf) {
                verified = new Verified(null, file.sha256Original(), file.bytesOriginal());
            } else {
                SanitizeResult result = sanitize(file, copy);
                meters.counter(REENCODE_METRIC, "resultado", result.resultado()).increment();
                switch (result) {
                    case SanitizeResult.Refused refused -> {
                        // the decoder can't read it: permanent, so no retry and no further wake-ups
                        refuse(file, owner, MotivoRecusa.ARQUIVO_INVALIDO, null, "recusado_invalido");
                        return;
                    }
                    case SanitizeResult.Unavailable unavailable -> {
                        retryOrRefuse(file, owner);
                        return;
                    }
                    case SanitizeResult.Sanitized photo -> verified = new Verified(photo.bytes(), sha256(photo.bytes()),
                            photo.bytes().length);
                }
            }
            accept(file, owner, verified, copy);
        } catch (DocumentStorageException | IOException e) {
            log.warn("event=scan_retry document_id={} error={}", file.id(), e.getClass().getSimpleName());
            retryOrRefuse(file, owner);
        } catch (LeaseLost e) {
            log.info("event=scan_lease_lost document_id={}", file.id());
        } finally {
            deleteQuietly(copy);
        }
    }

    /**
     * Reads the quarantined object once into a temp file, at most one byte past the size that was
     * sent, and checks it is exactly the file that was sent (size and SHA-256); the scan, the sanitizer
     * and the PDF copy then all read this one verified copy. A mismatch refuses the file generically
     * ({@code VERIFICACAO_FALHOU}, so the seller sends it again) and returns null.
     */
    private Path verifiedCopy(Document file, String owner) throws IOException {
        Path copy = Files.createTempFile(tempDir, "scan-", ".part");
        MessageDigest digest = digest();
        long copied;
        try (InputStream source = storage.get(DocumentStorage.Area.QUARANTINE, file.quarentenaKey());
                OutputStream target = Files.newOutputStream(copy)) {
            copied = new DigestInputStream(source, digest).transferTo(new BoundedOutputStream(target,
                    file.bytesOriginal() + 1));
        } catch (OversizedObject e) {
            copied = -1;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(copy);
            throw e;
        }
        if (copied == file.bytesOriginal() && HexFormat.of().formatHex(digest.digest()).equals(file.sha256Original())) {
            return copy;
        }
        deleteQuietly(copy);
        log.warn("event=document_refused motivo=integridade document_id={}", file.id());
        refuse(file, owner, MotivoRecusa.VERIFICACAO_FALHOU, null, "recusado_integridade");
        return null;
    }

    /** Stops a copy one byte past its limit, so an oversized object is never read whole. */
    private static final class BoundedOutputStream extends FilterOutputStream {
        private long remaining;

        BoundedOutputStream(OutputStream out, long limit) {
            super(out);
            this.remaining = limit;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > remaining) {
                out.write(bytes, offset, (int) remaining);
                remaining = 0;
                throw new OversizedObject();
            }
            out.write(bytes, offset, length);
            remaining -= length;
        }
    }

    /** The quarantined object is larger than the file that was sent. */
    private static final class OversizedObject extends IOException {
        OversizedObject() {
            super("larger than the file that was sent", null);
        }
    }

    /**
     * Put back for a later session without counting the attempt (stale scanner signatures); the
     * session's alert covers it.
     */
    public void rescheduleUncounted(Document file, String owner) {
        if (claims.rescheduleUncounted(file.id(), owner, now().plus(settings.backoffAfter(1)))) {
            meters.counter(RESULT_METRIC, "resultado", "reagendado").increment();
        }
    }

    /** Retried later; refused as not verifiable once the attempts run out. */
    public void retryOrRefuse(Document file, String owner) {
        if (file.tentativas() >= settings.maxAttempts()) {
            log.error("event=document_refused motivo=verificacao_falhou document_id={} tentativas={}", file.id(),
                    file.tentativas());
            try {
                refuse(file, owner, MotivoRecusa.VERIFICACAO_FALHOU, null, "recusado_falhou");
            } catch (LeaseLost e) {
                log.info("event=scan_lease_lost document_id={}", file.id());
            }
            return;
        }
        if (claims.reschedule(file.id(), owner, now().plus(settings.backoffAfter(file.tentativas())))) {
            meters.counter(RESULT_METRIC, "resultado", "reagendado").increment();
        }
    }

    private SanitizeResult sanitize(Document file, Path copy) throws IOException {
        ImageHeaderCheck.Format format = "image/png".equals(file.contentType()) ? ImageHeaderCheck.Format.PNG
                : ImageHeaderCheck.Format.JPEG;
        try (InputStream content = Files.newInputStream(copy)) {
            return sanitizer.sanitize(content, file.bytesOriginal(), format);
        }
    }

    private void accept(Document file, String owner, Verified verified, Path copy) throws IOException {
        String key = DocumentStorage.attemptKey(file.workspaceId(), file.caseId(), file.id(), owner);
        if (verified.sanitized() == null) {
            try (InputStream content = Files.newInputStream(copy)) { // a PDF is stored as sent
                storage.put(DocumentStorage.Area.FINAL, key, content, file.bytesOriginal(), file.contentType(),
                        Map.of());
            }
        } else {
            storage.put(DocumentStorage.Area.FINAL, key, new ByteArrayInputStream(verified.sanitized()),
                    verified.size(), file.contentType(), Map.of());
        }
        String sha256 = verified.sha256();
        try {
            TransactionRetry.DEFAULT.execute(() -> transactions.execute(status -> {
                if (!claims.accept(file.id(), owner, key, sha256, verified.size())) {
                    throw new LeaseLost();
                }
                Instant now = now();
                if (!cases.recordItem(file.caseId(), file.tipo(), itemState(file), now, false)) {
                    throw new CaseGone();
                }
                mongo.insert(new CaseEvent(UUID.randomUUID(), file.caseId(), TipoEventoCaso.documento_recebido,
                        new CaseEvent.Dados(file.tipo()), now));
                audit(file, TipoAuditoria.DOCUMENTO_ACEITO, sha256, verified.size(), null);
                return null;
            }));
        } catch (LeaseLost | CaseGone e) {
            // this attempt's own key only: another worker's object is never touched
            Document now = mongo.findById(file.id(), Document.class);
            if (now == null || !key.equals(now.storageKey())) {
                deleteQuietly(DocumentStorage.Area.FINAL, key);
            }
            if (e instanceof CaseGone) {
                log.info("event=scan_case_gone document_id={}", file.id());
                retryOrRefuse(file, owner);
                return;
            }
            throw e;
        }
        meters.counter(RESULT_METRIC, "resultado", "aceito").increment();
        deleteQuietly(DocumentStorage.Area.QUARANTINE, file.quarentenaKey());
        claims.quarantineDeleted(file.id());
    }

    private void refuse(Document file, String owner, MotivoRecusa motivo, String signature, String resultado) {
        TransactionRetry.DEFAULT.execute(() -> transactions.execute(status -> {
            if (!claims.refuse(file.id(), owner, motivo)) {
                throw new LeaseLost();
            }
            Instant now = now();
            cases.releaseAndRecord(file.caseId(), file.tipo(), file.bytesOriginal(), itemState(file), now);
            audit(file, TipoAuditoria.DOCUMENTO_RECUSADO, null, null, signature);
            return null;
        }));
        meters.counter(RESULT_METRIC, "resultado", resultado).increment();
        deleteQuietly(DocumentStorage.Area.QUARANTINE, file.quarentenaKey());
    }

    private DocumentItemState itemState(Document file) {
        List<Document> files = cases.files(file.caseId(), file.tipo());
        return DocumentItemState.of(files).orElseThrow();
    }

    private void audit(Document file, TipoAuditoria tipo, String sha256, Long bytesArmazenados, String signature) {
        mongo.insert(new AuditEvent(UUID.randomUUID(), file.workspaceId(), tipo, Ator.sistema, file.caseId(), now(),
                null, true, null, null, ResumoAuditoria.ofDocumento(new ResumoDocumento(file.tipo(),
                        List.of(new ResumoDocumento.Arquivo(file.sha256Original(), sha256, file.bytesOriginal(),
                                bytesArmazenados)), signature))));
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // the temp directory is wiped at startup
        }
    }

    private void deleteQuietly(DocumentStorage.Area area, String key) {
        if (key == null) {
            return;
        }
        try {
            storage.delete(area, key);
        } catch (RuntimeException e) {
            // the orphan check removes it later
            log.warn("event=storage_delete_deferred area={} error={}", area, e.getClass().getSimpleName());
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
