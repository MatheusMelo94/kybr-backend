package com.matheusmelo.kybr.service.upload.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.document.MotivoRecusa;
import com.matheusmelo.kybr.model.document.SituacaoEnvio;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.CaseEvent;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.repository.CaseDocumentWrites;
import com.matheusmelo.kybr.repository.ScanClaims;
import com.matheusmelo.kybr.service.image.SanitizeResult;
import com.matheusmelo.kybr.service.scan.ScannerWake;
import com.matheusmelo.kybr.service.scan.SignatureMonitor;
import com.matheusmelo.kybr.service.storage.DocumentStorage;
import com.matheusmelo.kybr.support.MovableClock;
import com.matheusmelo.kybr.support.upload.TestImages;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import com.matheusmelo.kybr.support.upload.UploadWebIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The scan job end to end on the scripted scanner, storage and sanitizer: a received file is scanned,
 * re-encoded when a photo, and accepted or refused, with the case's D4 item, counters, events and
 * audit to match; retries back off up to the attempt ceiling; a revoked case accepts nothing; a
 * session wakes the scanner once and stops on a scanner that never wakes or stale signatures.
 */
class ScanJobIT extends UploadWebIntegrationTest {

    @Autowired
    private ScanClaims claims;
    @Autowired
    private ScanWorker worker;
    @Autowired
    private ScanDispatcher dispatcher;
    @Autowired
    private KybrProperties properties;
    @Autowired
    private MeterRegistry meters;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private CaseDocumentWrites caseWrites;

    @BeforeEach
    void noOtherFileIsDue() {
        mongo.updateMulti(query(where(Document.ESTADO).in(EstadoDocumento.RECEBIDO, EstadoDocumento.VERIFICANDO)),
                new Update().set(Document.ESTADO, EstadoDocumento.RECUSADO).unset(Document.ATIVO), Document.class);
    }

    @Test
    void aCleanPdfIsAcceptedStoredAsReceivedAndShownAsAccepted() {
        byte[] pdf = TestPdfs.plain();
        Document file = receive(FormatoEnvio.pdf, pdf, "application/pdf");
        double accepted = count(ScanWorker.RESULT_METRIC, "aceito");

        dispatcher.runOnce();

        Document stored = stored(file);
        assertThat(stored.estado()).isEqualTo(EstadoDocumento.ACEITO);
        assertThat(stored.storageKey()).startsWith(DocumentStorage.key(workspace.id(), pending.linkCase().id(), file.id())
                + "/");
        assertThat(stored.sha256()).isEqualTo(sha256(pdf));
        assertThat(stored.bytesArmazenados()).isEqualTo(pdf.length);
        assertThat(stored.quarentenaKey()).isNull();
        assertThat(storage.bytes(stored.storageKey())).isEqualTo(pdf);
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(item()).satisfies(item -> {
            assertThat(item.situacaoEnvio()).isEqualTo(SituacaoEnvio.aceito);
            assertThat(item.documentosEnviados()).isEqualTo(1);
            assertThat(item.documentosRecusados()).isZero();
            assertThat(item.formatoEnvio()).isEqualTo(FormatoEnvio.pdf);
        });
        assertThat(mongo.find(query(where("case_id").is(pending.linkCase().id())), CaseEvent.class)).hasSize(1);
        assertThat(audits(TipoAuditoria.DOCUMENTO_ACEITO)).singleElement().satisfies(audit ->
                assertThat(audit.resumo().documento().arquivos().getFirst().sha256()).isEqualTo(sha256(pdf)));
        assertThat(count(ScanWorker.RESULT_METRIC, "aceito")).isEqualTo(accepted + 1);
    }

    @Test
    void aCleanPhotoIsStoredReEncodedWithBothHashesAudited() {
        byte[] photo = TestImages.jpegWithMetadata(64, 48);
        Document file = receive(FormatoEnvio.fotos, photo, "image/jpeg");

        dispatcher.runOnce();

        Document stored = stored(file);
        byte[] kept = storage.bytes(stored.storageKey());
        assertThat(TestImages.hasGps(kept)).isFalse();
        assertThat(stored.sha256()).isEqualTo(sha256(kept)).isNotEqualTo(file.sha256Original());
        assertThat(audits(TipoAuditoria.DOCUMENTO_ACEITO)).singleElement().satisfies(audit -> {
            var arquivo = audit.resumo().documento().arquivos().getFirst();
            assertThat(arquivo.sha256Original()).isEqualTo(file.sha256Original());
            assertThat(arquivo.sha256()).isEqualTo(sha256(kept));
        });
    }

    @Test
    void aDetectionRefusesTheFileGenericallyReleasesItsShareAndAuditsTheSignature() {
        Document file = receive(FormatoEnvio.pdf, TestPdfs.withScannerMarker(), "application/pdf");
        double malware = meters.counter(ScanWorker.MALWARE_METRIC).count();

        dispatcher.runOnce();

        Document stored = stored(file);
        assertThat(stored.estado()).isEqualTo(EstadoDocumento.RECUSADO);
        assertThat(stored.motivoRecusa()).isEqualTo(MotivoRecusa.CONTEUDO_NAO_PERMITIDO);
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(storage.keys()).isEmpty();
        assertThat(caseNow().documentos()).doesNotContainKey(TIPO);
        assertThat(caseNow().arquivosTotal()).isZero();
        assertThat(item()).satisfies(item -> {
            assertThat(item.situacaoEnvio()).isEqualTo(SituacaoEnvio.recusado);
            assertThat(item.motivoRecusa()).isEqualTo(MotivoRecusa.CONTEUDO_NAO_PERMITIDO);
            assertThat(item.documentosEnviados()).isZero();
            assertThat(item.documentosRecusados()).isEqualTo(1);
            assertThat(item.formatoEnvio()).isNull();
        });
        assertThat(audits(TipoAuditoria.DOCUMENTO_RECUSADO)).singleElement().satisfies(audit ->
                assertThat(audit.resumo().documento().assinatura()).isEqualTo("Kybr.Test.ScannerMarker"));
        assertThat(meters.counter(ScanWorker.MALWARE_METRIC).count()).isEqualTo(malware + 1);
    }

    @Test
    void theQuarantinedObjectIsReadOnceAndThatOneCopyFeedsTheScanAndTheSanitizer() {
        receive(FormatoEnvio.fotos, TestImages.jpeg(18, 18), "image/jpeg");
        int before = storage.gets();

        dispatcher.runOnce();

        assertThat(storage.gets() - before).isEqualTo(1);
        assertThat(storage.keys()).hasSize(1);
    }

    @Test
    void aQuarantinedObjectThatIsntTheFileSentIsRefusedGenericallyWithoutAScan() {
        Document file = receive(FormatoEnvio.pdf, TestPdfs.plain(), "application/pdf");
        byte[] other = TestPdfs.withJavaScript();
        storage.put(DocumentStorage.Area.QUARANTINE, file.quarentenaKey(), new ByteArrayInputStream(other),
                other.length, "application/pdf", Map.of()); // the object no longer matches its recorded hash
        double integrity = count(ScanWorker.RESULT_METRIC, "recusado_integridade");

        dispatcher.runOnce();

        Document stored = stored(file);
        assertThat(stored.estado()).isEqualTo(EstadoDocumento.RECUSADO);
        assertThat(stored.motivoRecusa()).isEqualTo(MotivoRecusa.VERIFICACAO_FALHOU);
        assertThat(scanner.calls()).isZero();
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(storage.keys()).isEmpty();
        assertThat(audits(TipoAuditoria.DOCUMENTO_RECUSADO)).hasSize(1);
        assertThat(count(ScanWorker.RESULT_METRIC, "recusado_integridade")).isEqualTo(integrity + 1);
    }

    @Test
    void aQuarantinedObjectLargerThanTheFileSentIsRefusedWithoutReadingItWhole() {
        byte[] pdf = TestPdfs.plain();
        Document file = receive(FormatoEnvio.pdf, pdf, "application/pdf");
        byte[] longer = java.util.Arrays.copyOf(pdf, pdf.length + 1_000_000);
        storage.put(DocumentStorage.Area.QUARANTINE, file.quarentenaKey(), new ByteArrayInputStream(longer),
                longer.length, "application/pdf", Map.of());

        dispatcher.runOnce();

        assertThat(stored(file).motivoRecusa()).isEqualTo(MotivoRecusa.VERIFICACAO_FALHOU);
        assertThat(scanner.calls()).isZero();
    }

    @Test
    void aPhotoTheSanitizerCantDecodeIsRefusedAsInvalidOnTheFirstAttemptWithoutRetrying() {
        sanitizer.answering(new SanitizeResult.Refused());
        Document file = receive(FormatoEnvio.fotos, TestImages.jpeg(10, 10), "image/jpeg");

        dispatcher.runOnce();

        Document stored = stored(file);
        assertThat(stored.estado()).isEqualTo(EstadoDocumento.RECUSADO);
        assertThat(stored.motivoRecusa()).isEqualTo(MotivoRecusa.ARQUIVO_INVALIDO);
        assertThat(stored.tentativas()).isEqualTo(1);
        assertThat(sanitizer.calls()).isEqualTo(1);
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(caseNow().arquivosTotal()).isZero();
        assertThat(item().motivoRecusa()).isEqualTo(MotivoRecusa.ARQUIVO_INVALIDO);
    }

    @Test
    void eachAttemptWritesItsOwnFinalKeyAndALateLoserDeletesOnlyItsOwn() {
        byte[] pdf = TestPdfs.plain();
        Document file = receive(FormatoEnvio.pdf, pdf, "application/pdf");
        Document first = claims.claimNext(6, Instant.now(), "tentativa-a", Duration.ofMinutes(5)).orElseThrow();
        Document second = claims.claimNext(6, Instant.now().plus(Duration.ofMinutes(6)), "tentativa-b",
                Duration.ofMinutes(5)).orElseThrow(); // the first lease expired: taken over

        worker.process(second, "tentativa-b");
        worker.process(first, "tentativa-a");

        String winner = DocumentStorage.key(workspace.id(), pending.linkCase().id(), file.id()) + "/tentativa-b";
        assertThat(stored(file).storageKey()).isEqualTo(winner);
        assertThat(storage.keys()).containsExactly(winner);
        assertThat(storage.bytes(winner)).isEqualTo(pdf);
    }

    @Test
    void anUnavailableScannerBacksOffAndTheSixthFailureRefusesTheFileAsNotVerifiable() {
        scanner.unavailable();
        Document file = receive(FormatoEnvio.pdf, TestPdfs.plain(), "application/pdf");
        MovableClock clock = new MovableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS).plusMillis(1));
        ScanWorker timed = worker(clock);
        List<Duration> waits = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofHours(1), Duration.ofHours(3));

        for (Duration wait : waits) {
            Document claimed = claims.claimNext(6, clock.instant(), "dono", Duration.ofMinutes(5)).orElseThrow();
            timed.process(claimed, "dono");
            assertThat(stored(file).estado()).isEqualTo(EstadoDocumento.RECEBIDO);
            assertThat(stored(file).proximaTentativa()).isEqualTo(clock.instant().plus(wait));
            clock.advance(wait);
        }
        Document last = claims.claimNext(6, clock.instant(), "dono", Duration.ofMinutes(5)).orElseThrow();
        timed.process(last, "dono");

        Document stored = stored(file);
        assertThat(stored.tentativas()).isEqualTo(6);
        assertThat(stored.estado()).isEqualTo(EstadoDocumento.RECUSADO);
        assertThat(stored.motivoRecusa()).isEqualTo(MotivoRecusa.VERIFICACAO_FALHOU);
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(caseNow().arquivosTotal()).isZero();
        assertThat(item().motivoRecusa()).isEqualTo(MotivoRecusa.VERIFICACAO_FALHOU);
    }

    @Test
    void aFileWhoseLastAttemptDiedIsRefusedAtTheCeilingWithoutWakingTheScanner() {
        Document file = receive(FormatoEnvio.pdf, TestPdfs.plain(), "application/pdf");
        mongo.updateFirst(query(where("_id").is(file.id())), new Update().set(Document.TENTATIVAS, 6), Document.class);

        dispatcher.runOnce();

        assertThat(stored(file).estado()).isEqualTo(EstadoDocumento.RECUSADO);
        assertThat(stored(file).motivoRecusa()).isEqualTo(MotivoRecusa.VERIFICACAO_FALHOU);
        assertThat(stored(file).tentativas()).isEqualTo(6);
        assertThat(scanner.pings()).isZero();
        assertThat(scanner.calls()).isZero();
        assertThat(storage.quarantineKeys()).isEmpty();
    }

    @Test
    void aStorageFailureIsRetriedNotRefused() {
        storage.failGetOnCall(1);
        Document file = receive(FormatoEnvio.pdf, TestPdfs.plain(), "application/pdf");

        dispatcher.runOnce();

        assertThat(stored(file).estado()).isEqualTo(EstadoDocumento.RECEBIDO);
        assertThat(stored(file).tentativas()).isEqualTo(1);
    }

    @Test
    void aRevokedTokenAtCommitAcceptsNothingAndLeavesNoFinalObject() {
        Document file = receive(FormatoEnvio.pdf, TestPdfs.plain(), "application/pdf");
        mongo.updateFirst(query(where("_id").is(pending.linkCase().id())),
                new Update().set(LinkCase.TOKEN_REVOGADO_EM, Instant.now()), LinkCase.class);

        dispatcher.runOnce();

        assertThat(stored(file).estado()).isEqualTo(EstadoDocumento.RECEBIDO);
        assertThat(storage.keys()).isEmpty();
        assertThat(audits(TipoAuditoria.DOCUMENTO_ACEITO)).isEmpty();
    }

    @Test
    void aWorkerThatLostItsLeaseWritesNothingAndRemovesItsFinalObject() {
        Document file = receive(FormatoEnvio.pdf, TestPdfs.plain(), "application/pdf");
        Document claimed = claims.claimNext(6, Instant.now(), "dono-atual", Duration.ofMinutes(5)).orElseThrow();

        worker.process(claimed, "dono-antigo");

        assertThat(stored(file).estado()).isEqualTo(EstadoDocumento.VERIFICANDO);
        assertThat(stored(file).leaseOwner()).isEqualTo("dono-atual");
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void aReRunAfterACrashBetweenStoreAndCommitEndsTheSameWithOnePage() {
        byte[] pdf = TestPdfs.plain();
        Document file = receive(FormatoEnvio.pdf, pdf, "application/pdf");
        String finalKey = DocumentStorage.key(workspace.id(), pending.linkCase().id(), file.id()) + "/tentativa-que-caiu";
        storage.put(DocumentStorage.Area.FINAL, finalKey, new ByteArrayInputStream(pdf), pdf.length, "application/pdf",
                Map.of()); // what a crashed run left behind

        dispatcher.runOnce();

        assertThat(storage.keys()).contains(stored(file).storageKey());
        assertThat(stored(file).estado()).isEqualTo(EstadoDocumento.ACEITO);
        assertThat(item().documentosEnviados()).isEqualTo(1);
    }

    @Test
    void aSessionWakesTheScannerOnceForSeveralFiles() {
        scanner.coldStart(3);
        receive(FormatoEnvio.fotos, TestImages.jpeg(10, 10), "image/jpeg");
        receive(FormatoEnvio.fotos, TestImages.jpeg(12, 10), "image/jpeg");
        receive(FormatoEnvio.fotos, TestImages.jpeg(14, 10), "image/jpeg");
        MovableClock clock = new MovableClock(Instant.now());

        dispatcher(clock, new ScannerWake(scanner, Duration.ofSeconds(120), Duration.ofSeconds(5), clock,
                clock::advance, new SimpleMeterRegistry()), SignatureMonitor.withoutScanner()).runOnce();

        assertThat(scanner.pings()).isEqualTo(3);
        assertThat(scanner.calls()).isEqualTo(3);
        assertThat(mongo.count(query(where(Document.CASE_ID).is(pending.linkCase().id())
                .and(Document.ESTADO).is(EstadoDocumento.ACEITO)), Document.class)).isEqualTo(3);
    }

    @Test
    void aScannerThatNeverWakesScansNothingAndTheFileWaitsForALaterSession() {
        scanner.neverWakes();
        Document file = receive(FormatoEnvio.pdf, TestPdfs.plain(), "application/pdf");
        MovableClock clock = new MovableClock(Instant.now());

        dispatcher(clock, new ScannerWake(scanner, Duration.ofSeconds(120), Duration.ofSeconds(5), clock,
                clock::advance, new SimpleMeterRegistry()), SignatureMonitor.withoutScanner()).runOnce();

        assertThat(scanner.calls()).isZero();
        assertThat(stored(file).estado()).isEqualTo(EstadoDocumento.RECEBIDO);
        assertThat(stored(file).tentativas()).isEqualTo(1);
    }

    @Test
    void staleSignaturesScanNothingAndTheFileWaits() {
        Document file = receive(FormatoEnvio.pdf, TestPdfs.plain(), "application/pdf");
        SignatureMonitor stale = new SignatureMonitor(() -> Optional.of(Instant.now().minus(Duration.ofDays(3))),
                Duration.ofHours(48), Clock.systemUTC(), new SimpleMeterRegistry());

        dispatcher(Clock.systemUTC(), new ScannerWake(scanner, Duration.ofSeconds(120), Duration.ofSeconds(5),
                Clock.systemUTC(), d -> { }, new SimpleMeterRegistry()), stale).runOnce();

        assertThat(scanner.calls()).isZero();
        assertThat(stored(file).estado()).isEqualTo(EstadoDocumento.RECEBIDO);
        assertThat(stored(file).tentativas()).as("a stale-signature reschedule isn't an attempt").isZero();
    }

    @Test
    void anIdleBacklogNeverTouchesTheScanner() {
        for (int i = 0; i < 5; i++) {
            dispatcher.runOnce();
        }

        assertThat(scanner.pings()).isZero();
        assertThat(scanner.calls()).isZero();
    }

    @Test
    void theBacklogGaugesFollowTheWaitingFiles() {
        receive(FormatoEnvio.pdf, TestPdfs.plain(), "application/pdf");
        scanner.neverWakes();
        MovableClock clock = new MovableClock(Instant.now().plus(Duration.ofMinutes(20)));
        SimpleMeterRegistry gauges = new SimpleMeterRegistry();
        ScanDispatcher measured = new ScanDispatcher(claims, worker(clock), new ScannerWake(scanner,
                Duration.ofSeconds(10), Duration.ofSeconds(5), clock, clock::advance, new SimpleMeterRegistry()),
                SignatureMonitor.withoutScanner(), properties, clock, gauges);

        measured.runOnce();

        assertThat(gauges.get(ScanDispatcher.BACKLOG_GAUGE).tag("estado", "recebido").gauge().value()).isEqualTo(1);
        assertThat(gauges.get(ScanDispatcher.OLDEST_GAUGE).gauge().value()).isGreaterThanOrEqualTo(20 * 60);
        assertThat(gauges.get(ScanDispatcher.STUCK_GAUGE).gauge().value()).isZero();
    }

    // --- helpers -------------------------------------------------------------------------------------

    private Document receive(FormatoEnvio formato, byte[] content, String contentType) {
        UUID id = UUID.randomUUID();
        UUID caseId = pending.linkCase().id();
        String key = DocumentStorage.key(workspace.id(), caseId, id);
        storage.put(DocumentStorage.Area.QUARANTINE, key, new ByteArrayInputStream(content), content.length, contentType,
                Map.of());
        mongo.updateFirst(query(where("_id").is(caseId)), new Update()
                .set(LinkCase.documentosField(TIPO, DocumentosTipo.FORMATO), formato)
                .inc(LinkCase.documentosField(TIPO, DocumentosTipo.ARQUIVOS), 1)
                .inc(LinkCase.documentosField(TIPO, DocumentosTipo.BYTES), (long) content.length)
                .inc(LinkCase.ARQUIVOS_TOTAL, 1), LinkCase.class);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return mongo.insert(Document.recebido(id, workspace.id(), caseId, null, TIPO, formato, 1, contentType,
                content.length, sha256(content), now, key, UUID.randomUUID()));
    }

    private ScanWorker worker(Clock clock) {
        return new ScanWorker(claims, caseWrites, storage, scanner, sanitizer, mongo, transactionManager, properties,
                clock, new SimpleMeterRegistry());
    }

    private ScanDispatcher dispatcher(Clock clock, ScannerWake wake, SignatureMonitor signatures) {
        return new ScanDispatcher(claims, worker(clock), wake, signatures, properties, clock, new SimpleMeterRegistry());
    }

    private Document stored(Document file) {
        return mongo.findById(file.id(), Document.class);
    }

    private LinkCase caseNow() {
        return mongo.findById(pending.linkCase().id(), LinkCase.class);
    }

    private ItemResultado item() {
        return caseNow().resultado().itens().stream().filter(item -> item.documentoTipo() == TIPO).findFirst()
                .orElseThrow();
    }

    private List<AuditEvent> audits(TipoAuditoria tipo) {
        return mongo.find(query(where("case_id").is(pending.linkCase().id()).and("tipo").is(tipo)), AuditEvent.class);
    }

    private double count(String metric, String resultado) {
        var counter = meters.find(metric).tag("resultado", resultado).counter();
        return counter == null ? 0 : counter.count();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
