package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.acceptance.upload.fixtures.SampleFiles;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.CaseEvent;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.EstadoRetencao;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.repository.ScanClaims;
import com.matheusmelo.kybr.service.image.SanitizeResult;
import com.matheusmelo.kybr.service.jobs.JobName;
import com.matheusmelo.kybr.service.scan.StubScanner;
import com.matheusmelo.kybr.service.upload.scan.ScanWorker;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.upload.ScriptedScanner;
import com.matheusmelo.kybr.support.upload.TestFiles;
import com.matheusmelo.kybr.support.upload.TestImages;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The scan job end to end: received files wait in quarantine, never readable, until a session
 * wakes the scanner (once, however many files wait), verifies each file on its own and either
 * accepts it (final object, audit, case event) or refuses it (quarantine deleted, its share of the
 * limits given back). An unavailable scanner, a scanner that doesn't wake or a failed store retry
 * with backoff, at most six attempts in all, then the file is refused as not verifiable. The backlog
 * gauges follow the waiting files, and nothing touches a scaled-down scanner while nothing waits.
 */
class ScanJobAcceptanceIT extends ScanJobAcceptanceTest {

    private static final int MAX_ATTEMPTS = 6;

    @Autowired
    private ScanClaims claims;
    @Autowired
    private ScanWorker worker;

    private UUID caseId() {
        return pending.linkCase().id();
    }

    private List<AuditEvent> audits(TipoAuditoria tipo) {
        return auditsOf(caseId()).stream().filter(a -> a.tipo() == tipo).toList();
    }

    // --- the request never verifies --------------------------------------------------------------

    @Test
    void theUploadAnswers202WithoutTouchingTheScannerOrThePhotoCleaner() {
        Response response = receive(pending, TIPO, SampleFiles.pdf("recebido"));

        assertReceived(response, TIPO, 1);
        assertThat(response.header("ETag")).isNull();
        assertThat(scanner.calls()).isZero();
        assertThat(scanner.pings()).isZero();
        assertThat(sanitizer.calls()).isZero();
        Document file = onlyFile(caseId());
        assertThat(file.estado()).isEqualTo(EstadoDocumento.RECEBIDO);
        assertThat(file.storageKey()).isNull();
        assertThat(file.quarentenaKey()).isEqualTo(workspace.id() + "/" + caseId() + "/" + file.id());
        assertThat(storage.quarantineKeys()).containsExactly(file.quarentenaKey());
        assertThat(storage.keys()).as("nothing in the final area before a verdict").isEmpty();
    }

    @Test
    void readingTheCaseWhileFilesWaitNeverWakesTheScanner() {
        assertReceived(receive(pending, TIPO, SampleFiles.photo("espera")), TIPO, 1);
        String quarantineKey = onlyFile(caseId()).quarentenaKey();

        for (int i = 0; i < 60; i++) {
            Response read = readCase(workspace.slug(), pending.token());
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(read.asString()).doesNotContain(quarantineKey).doesNotContain(caseId() + "/");
        }

        assertThat(scanner.pings()).isZero();
        assertThat(scanner.calls()).isZero();
    }

    @Test
    void aHeldScannerNeverMakesTheUploadUnavailable() {
        ScriptedScanner.Gate gate = scanner.holdScans();
        try {
            for (int i = 0; i < 20; i++) {
                TestCases.Built linkCase = i % 2 == 0 ? pending : seedCase(EstadoCaso.pending);
                assertThat(receive(linkCase, TIPO, SampleFiles.photo("segura-" + i)).statusCode()).isEqualTo(202);
            }
        } finally {
            gate.release();
        }
    }

    @Test
    void nothingTouchesTheScannerWhileNothingWaits() {
        for (int i = 0; i < 10; i++) {
            scanAll();
        }

        assertThat(scanner.pings()).isZero();
        assertThat(scanner.calls()).isZero();
    }

    // --- accepted --------------------------------------------------------------------------------

    @Test
    void aCleanPdfIsAcceptedStoredAsSentAuditedAndCounted() {
        byte[] pdf = SampleFiles.pdf("aceito");
        double accepted = metric("kybr_scan_result_total", "resultado=\"aceito\"");
        assertReceived(receive(pending, TIPO, pdf), TIPO, 1);

        scanAll();

        Document file = onlyFile(caseId());
        assertThat(file.estado()).isEqualTo(EstadoDocumento.ACEITO);
        assertThat(storage.bytes(file.storageKey())).isEqualTo(pdf);
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(file.sha256()).isEqualTo(file.sha256Original());
        assertThat(audits(TipoAuditoria.DOCUMENTO_ACEITO)).singleElement().satisfies(audit -> {
            assertThat(audit.resumo().documento().arquivos().getFirst().sha256()).isEqualTo(file.sha256());
            assertThat(audit.resumo().documento().arquivos().getFirst().sha256Original()).isEqualTo(file.sha256Original());
        });
        assertThat(mongo.find(query(where("case_id").is(caseId())), CaseEvent.class)).hasSize(1);
        assertThat(scanner.calls()).isEqualTo(1);
        assertThat(sanitizer.calls()).isZero();
        assertThat(metric("kybr_scan_result_total", "resultado=\"aceito\"")).isEqualTo(accepted + 1);
        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "aceito").containsEntry("formato_envio", "pdf");
    }

    @Test
    void aCleanPhotoIsCleanedAfterTheScanAndStoredWithoutItsMetadata() {
        byte[] photo = TestImages.withJpegMetadata(TestImages.jpeg(30, 20));
        assertReceived(receive(pending, TIPO, photo), TIPO, 1);

        scanAll();

        Document file = onlyFile(caseId());
        assertThat(file.estado()).isEqualTo(EstadoDocumento.ACEITO);
        assertThat(scanner.calls()).isEqualTo(1);
        assertThat(sanitizer.calls()).isEqualTo(1);
        byte[] stored = storage.bytes(file.storageKey());
        assertThat(TestImages.hasExif(stored)).isFalse();
        assertThat(file.sha256()).isNotEqualTo(file.sha256Original());
        assertThat(storage.quarantineKeys()).isEmpty();
    }

    // --- refused for its content -------------------------------------------------------------------

    @Test
    void theScannerTestMarkerPdfIsRefusedGenericallyWithItsShareGivenBack() {
        double refused = metric("kybr_scan_result_total", "resultado=\"recusado_conteudo\"");
        double malware = metric("kybr_upload_malware_total");
        assertReceived(receive(pending, TIPO, TestPdfs.withScannerMarker()), TIPO, 1);

        scanAll();

        Document file = onlyFile(caseId());
        assertThat(file.estado()).isEqualTo(EstadoDocumento.RECUSADO);
        assertThat(file.motivoRecusa()).hasToString("CONTEUDO_NAO_PERMITIDO");
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(storage.keys()).isEmpty();
        assertThat(sanitizer.calls()).isZero();
        assertNothingReserved(caseId());
        assertThat(audits(TipoAuditoria.DOCUMENTO_RECUSADO)).singleElement()
                .satisfies(audit -> assertThat(audit.resumo().documento().assinatura()).isNotBlank());
        assertThat(metric("kybr_scan_result_total", "resultado=\"recusado_conteudo\"")).isEqualTo(refused + 1);
        assertThat(metric("kybr_upload_malware_total")).isEqualTo(malware + 1);
        Response read = readCase(workspace.slug(), pending.token());
        String signature = audits(TipoAuditoria.DOCUMENTO_RECUSADO).getFirst().resumo().documento().assinatura();
        assertThat(read.asString()).doesNotContain(signature).doesNotContainIgnoringCase("malware")
                .doesNotContainIgnoringCase("virus").doesNotContainIgnoringCase("infect");

        assertReceived(scanned(receive(pending, TIPO, SampleFiles.pdfOfExactSize(TEN_MIB))), TIPO, 1, "verificando");
        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "aceito");
    }

    @Test
    void activeContentAndEncryptedPdfsTheScannerFlagsAreRefusedTheSameWay() {
        List<byte[]> pdfs = List.of(TestPdfs.withJavaScript(), TestPdfs.withLaunch(), TestPdfs.encrypted());
        for (int i = 0; i < pdfs.size(); i++) {
            TestCases.Built linkCase = seedCase(EstadoCaso.pending);
            scanner.reset();
            scanner.infectedOnCall(1, "Kybr.PDF.Sintetico");
            assertReceived(receive(linkCase, TIPO, pdfs.get(i)), TIPO, 1);

            scanAll();

            assertThat(onlyFile(linkCase.linkCase().id()).motivoRecusa()).hasToString("CONTEUDO_NAO_PERMITIDO");
            assertThat(item(linkCase, TIPO)).containsEntry("situacao_envio", "recusado")
                    .containsEntry("motivo_recusa", "CONTEUDO_NAO_PERMITIDO");
        }
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void eachPhotoIsDecidedOnItsOwn() {
        byte[] flagged = SampleFiles.photoWithTrailer("segunda", StubScanner.TEST_MARKER);
        assertReceived(receive(pending, TIPO, SampleFiles.photo("primeira"), flagged, SampleFiles.photo("terceira")),
                TIPO, 3);

        scanAll();

        assertThat(documentsOf(caseId())).extracting(Document::estado).containsExactlyInAnyOrder(EstadoDocumento.ACEITO,
                EstadoDocumento.RECUSADO, EstadoDocumento.ACEITO);
        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "recusado").containsEntry("documentos_enviados", 2)
                .containsEntry("documentos_recusados", 1);
        assertThat(storage.keys()).hasSize(2);
    }

    @Test
    void aPhotoCarryingTheAntivirusTestStringAfterItsEndIsRefused() {
        byte[] photo = SampleFiles.photo("eicar");
        byte[] eicar = TestFiles.eicar();
        byte[] eicarInside = Arrays.copyOf(photo, photo.length + eicar.length);
        System.arraycopy(eicar, 0, eicarInside, photo.length, eicar.length);
        assertReceived(receive(pending, TIPO, eicarInside), TIPO, 1);

        scanAll();

        assertThat(onlyFile(caseId()).motivoRecusa()).hasToString("CONTEUDO_NAO_PERMITIDO");
        assertThat(sanitizer.calls()).as("the scan runs on the original, before any cleaning").isZero();
    }

    @Test
    void aPhotoTheCleanerCannotDecodeIsRefusedAsInvalidOnTheFirstAttempt() {
        sanitizer.answering(new SanitizeResult.Refused());
        assertReceived(receive(pending, TIPO, SampleFiles.photo("corrompida")), TIPO, 1);

        scanAll();

        Document file = onlyFile(caseId());
        assertThat(file.estado()).isEqualTo(EstadoDocumento.RECUSADO);
        assertThat(file.motivoRecusa()).hasToString("ARQUIVO_INVALIDO");
        assertThat(file.tentativas()).isEqualTo(1);
        assertThat(scanner.calls()).isEqualTo(1);
        assertThat(sanitizer.calls()).isEqualTo(1);
        assertThat(storage.quarantineKeys()).isEmpty();
        assertNothingReserved(caseId());
        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "recusado")
                .containsEntry("motivo_recusa", "ARQUIVO_INVALIDO");
    }

    @Test
    void aFileIdenticalToOneRefusedForItsContentIsRefusedAtOnce() {
        byte[] marked = TestPdfs.withScannerMarker();
        assertReceived(scanned(receive(pending, TIPO, marked)), TIPO, 1);

        assertError(receive(pending, TIPO, marked), 415, "FORMATO_INVALIDO");

        assertThat(scanner.calls()).isEqualTo(1);
        assertThat(storage.quarantineKeys()).isEmpty();
    }

    // --- waking the scanner --------------------------------------------------------------------------

    @Test
    void aColdScannerIsWokenOnceForAWholeBacklog() {
        TestCases.Built other = seedCase(EstadoCaso.pending);
        double woken = metric("kybr_scan_wake_seconds_count", "resultado=\"ok\"");
        scanner.coldStart(3);
        assertReceived(receive(pending, TIPO, SampleFiles.photo("a"), SampleFiles.photo("b")), TIPO, 2);
        assertReceived(receive(pending, OTHER_TIPO, SampleFiles.pdf("c")), OTHER_TIPO, 1);
        assertReceived(receive(other, TIPO, SampleFiles.photo("d"), SampleFiles.photo("e")), TIPO, 2);
        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "verificando");

        scanAll();

        assertThat(scanner.pings()).as("one wake-up for the session").isEqualTo(3);
        assertThat(scanner.calls()).isEqualTo(5);
        assertThat(documentsOf(caseId())).extracting(Document::estado).containsOnly(EstadoDocumento.ACEITO);
        assertThat(documentsOf(other.linkCase().id())).extracting(Document::estado).containsOnly(EstadoDocumento.ACEITO);
        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "aceito");
        assertThat(metric("kybr_scan_wake_seconds_count", "resultado=\"ok\"")).isEqualTo(woken + 1);
    }

    @Test
    void aScannerThatDoesNotWakeLeavesTheFileWaitingForItsNextAttempt() {
        double timeouts = metric("kybr_scan_wake_seconds_count", "resultado=\"timeout\"");
        scanner.neverWakes();
        assertReceived(receive(pending, TIPO, SampleFiles.photo("dormindo")), TIPO, 1);
        Instant before = Instant.now();

        scanAll();

        Document file = onlyFile(caseId());
        assertThat(file.estado()).isEqualTo(EstadoDocumento.RECEBIDO);
        assertThat(file.tentativas()).isEqualTo(1);
        assertThat(file.proximaTentativa()).isBetween(before.plus(Duration.ofSeconds(55)),
                Instant.now().plus(Duration.ofSeconds(65)));
        assertThat(scanner.calls()).isZero();
        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "verificando");
        assertThat(metric("kybr_scan_wake_seconds_count", "resultado=\"timeout\"")).isEqualTo(timeouts + 1);

        scanAll();
        assertThat(onlyFile(caseId()).tentativas()).as("never claimed before it is due").isEqualTo(1);
    }

    // --- retries and the ceiling ---------------------------------------------------------------------

    @Test
    void sixFailedAttemptsRefuseTheFileAsNotVerifiableAndThereIsNeverASeventh() {
        double failed = metric("kybr_scan_result_total", "resultado=\"recusado_falhou\"");
        assertReceived(receive(pending, TIPO, SampleFiles.pdfOfExactSize(TEN_MIB)), TIPO, 1);
        scanner.neverWakes();
        scanAll(); // attempt 1: the scanner doesn't wake
        scanner.coldStart(1);
        scanner.unavailable();

        for (int attempt = 2; attempt <= MAX_ATTEMPTS + 1; attempt++) {
            makeDue(caseId());
            scanAll();
        }

        Document file = onlyFile(caseId());
        assertThat(file.tentativas()).isEqualTo(MAX_ATTEMPTS);
        assertThat(scanner.calls()).isEqualTo(MAX_ATTEMPTS - 1);
        assertThat(file.estado()).isEqualTo(EstadoDocumento.RECUSADO);
        assertThat(file.motivoRecusa()).hasToString("VERIFICACAO_FALHOU");
        assertThat(storage.quarantineKeys()).isEmpty();
        assertNothingReserved(caseId());
        assertThat(audits(TipoAuditoria.DOCUMENTO_RECUSADO)).hasSize(1);
        assertThat(metric("kybr_scan_result_total", "resultado=\"recusado_falhou\"")).isEqualTo(failed + 1);
        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "recusado")
                .containsEntry("motivo_recusa", "VERIFICACAO_FALHOU").containsEntry("documentos_enviados", 0);
    }

    @Test
    void aFileRefusedAsNotVerifiableMayBeSentAgainAndIsVerifiedAfresh() {
        byte[] pdf = SampleFiles.pdf("de-novo");
        assertReceived(receive(pending, TIPO, pdf), TIPO, 1);
        scanner.unavailable();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            makeDue(caseId());
            scanAll();
        }
        assertThat(onlyFile(caseId()).motivoRecusa()).hasToString("VERIFICACAO_FALHOU");
        scanner.reset();

        assertReceived(scanned(receive(pending, TIPO, pdf)), TIPO, 1);

        assertThat(documentsOf(caseId())).extracting(Document::estado)
                .containsExactlyInAnyOrder(EstadoDocumento.RECUSADO, EstadoDocumento.ACEITO);
        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "aceito");
    }

    @Test
    void twoUnavailableAnswersThenACleanOneAcceptTheFileOnTheThirdAttempt() {
        assertReceived(receive(pending, TIPO, SampleFiles.pdf("terceira")), TIPO, 1);
        scanner.unavailable();
        scanAll();
        makeDue(caseId());
        scanAll();
        scanner.reset();
        makeDue(caseId());

        scanAll();

        Document file = onlyFile(caseId());
        assertThat(file.estado()).isEqualTo(EstadoDocumento.ACEITO);
        assertThat(file.tentativas()).isEqualTo(3);
        assertThat(storage.keys()).hasSize(1);
    }

    @Test
    void anUnavailablePhotoCleanerIsRetriedLikeTheScannerThenTheFileIsAccepted() {
        sanitizer.answering(new SanitizeResult.Unavailable(false));
        assertReceived(receive(pending, TIPO, SampleFiles.photo("limpeza")), TIPO, 1);

        scanAll();
        Document waiting = onlyFile(caseId());
        assertThat(waiting.estado()).isEqualTo(EstadoDocumento.RECEBIDO);
        assertThat(waiting.tentativas()).isEqualTo(1);
        assertThat(storage.keys()).isEmpty();
        sanitizer.reset();
        makeDue(caseId());
        scanAll();

        assertThat(onlyFile(caseId()).estado()).isEqualTo(EstadoDocumento.ACEITO);
        assertThat(storage.keys()).hasSize(1);
    }

    @Test
    void aFailedFinalStoreIsRetriedAndLeavesOneFinalObject() {
        assertReceived(receive(pending, TIPO, SampleFiles.pdf("armazenar")), TIPO, 1);
        storage.failPutOnCall(storage.puts() + 1);

        scanAll();
        assertThat(onlyFile(caseId()).estado()).isEqualTo(EstadoDocumento.RECEBIDO);
        assertThat(storage.quarantineKeys()).as("kept until the commit").hasSize(1);
        makeDue(caseId());
        scanAll();

        Document file = onlyFile(caseId());
        assertThat(file.estado()).isEqualTo(EstadoDocumento.ACEITO);
        assertThat(storage.keys()).containsExactly(file.storageKey());
        assertThat(storage.quarantineKeys()).isEmpty();
    }

    @Test
    void aWorkerThatLostItsLeaseNeverTouchesTheWinnersFinalObject() {
        byte[] pdf = SampleFiles.pdf("arrendamento");
        assertReceived(receive(pending, TIPO, pdf), TIPO, 1);
        Document stale = claims.claimNext(MAX_ATTEMPTS, Instant.now(), "dono-antigo", Duration.ofMinutes(5)).orElseThrow();
        mongo.updateFirst(query(where("_id").is(stale.id())),
                new Update().set(Document.LEASE_UNTIL, Instant.now().minusSeconds(1)), Document.class);
        scanAll(); // a new owner claims the expired lease and accepts the file
        Document won = onlyFile(caseId());
        assertThat(won.estado()).isEqualTo(EstadoDocumento.ACEITO);

        worker.process(stale, "dono-antigo"); // the stale worker finishes late

        Document after = onlyFile(caseId());
        assertThat(after.estado()).isEqualTo(EstadoDocumento.ACEITO);
        assertThat(after.storageKey()).isEqualTo(won.storageKey())
                .as("each attempt writes its own final key").startsWith(workspace.id() + "/" + caseId() + "/" + won.id() + "/");
        assertThat(storage.keys()).as("the stale attempt removed only its own object").containsExactly(won.storageKey());
        assertThat(storage.bytes(won.storageKey())).isEqualTo(pdf);
    }

    // --- quarantine and retention --------------------------------------------------------------------

    @Test
    void retentionPurgesFilesInEveryStateFromBothAreas() {
        assertReceived(scanned(receive(pending, TIPO, SampleFiles.photo("aceita"))), TIPO, 1);
        assertReceived(scanned(receive(pending, OTHER_TIPO, TestPdfs.withScannerMarker())), OTHER_TIPO, 1);
        assertReceived(receive(pending, TIPO, SampleFiles.photo("esperando")), TIPO, 2);
        assertThat(storage.keys()).hasSize(1);
        assertThat(storage.quarantineKeys()).hasSize(1);
        mongo.updateFirst(query(where("_id").is(caseId())),
                Update.update(LinkCase.RETENCAO_ATE, Instant.now().minusSeconds(1)), LinkCase.class);

        for (int run = 0; run < 10 && !concluded(caseId()); run++) {
            jobs.run(JobName.RETENTION);
        }
        scanAll();

        assertThat(concluded(caseId())).isTrue();

        assertThat(storage.keys()).isEmpty();
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(documentsOf(caseId())).isEmpty();
        assertError(readCase(workspace.slug(), pending.token()), 404, "CASO_NAO_ENCONTRADO");
    }

    private boolean concluded(UUID id) {
        LinkCase linkCase = stored(id);
        return linkCase.retencao() != null && linkCase.retencao().estado() == EstadoRetencao.CONCLUIDA;
    }

    // --- backlog signals -------------------------------------------------------------------------------

    @Test
    void theBacklogGaugesFollowTheWaitingFilesTheirAgeAndStuckOnes() {
        scanner.neverWakes();
        assertReceived(receive(pending, TIPO, SampleFiles.photo("1"), SampleFiles.photo("2")), TIPO, 2);
        assertReceived(receive(pending, OTHER_TIPO, SampleFiles.photo("3"), SampleFiles.photo("4")), OTHER_TIPO, 2);

        scanAll();
        assertThat(metric("kybr_scan_backlog", "estado=\"recebido\"")).isEqualTo(4);
        assertThat(metric("kybr_scan_backlog", "estado=\"verificando\"")).isZero();

        receivedAgo(caseId(), Duration.ofSeconds(1_000));
        scanAll();
        assertThat(metric("kybr_scan_backlog_oldest_age_seconds")).isBetween(900.0, 3_599.0);
        assertThat(metric("kybr_scan_stuck")).isZero();

        receivedAgo(caseId(), Duration.ofSeconds(4_000));
        scanAll();
        assertThat(metric("kybr_scan_backlog_oldest_age_seconds")).isGreaterThanOrEqualTo(3_600.0);

        receivedAgo(caseId(), Duration.ofHours(25));
        scanAll();
        assertThat(metric("kybr_scan_stuck")).isEqualTo(4);

        scanner.coldStart(1);
        makeDue(caseId());
        scanAll();
        assertThat(documentsOf(caseId())).extracting(Document::estado).containsOnly(EstadoDocumento.ACEITO);
        assertThat(metric("kybr_scan_backlog", "estado=\"recebido\"")).isZero();
        assertThat(metric("kybr_scan_backlog_oldest_age_seconds")).isZero();
        assertThat(metric("kybr_scan_stuck")).isZero();
    }
}
