package com.matheusmelo.kybr.service.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.document.MotivoRecusa;
import com.matheusmelo.kybr.model.document.SituacaoEnvio;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.CaseEvent;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.EstadoRetencao;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.ResumoDocumento;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.link.TipoEventoCaso;
import com.matheusmelo.kybr.repository.CaseDocumentWrites;
import com.matheusmelo.kybr.service.storage.DocumentStorage;
import com.matheusmelo.kybr.service.upload.scan.ScanDispatcher;
import com.matheusmelo.kybr.service.upload.scan.ScanWorker;
import com.matheusmelo.kybr.support.upload.ImageHeaders;
import com.matheusmelo.kybr.support.upload.ScriptedScanner;
import com.matheusmelo.kybr.support.upload.TestImages;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import com.matheusmelo.kybr.support.upload.UploadWebIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.PlatformTransactionManager;

/** The upload from its reservation to the commit: quarantine, one conditional transaction, and compensation. */
class UploadServiceCommitIT extends UploadWebIntegrationTest {

    @Autowired
    private UploadService service;
    @Autowired
    private MeterRegistry meters;
    @Autowired
    private ScanDispatcher scans;
    @Autowired
    private CaseDocumentWrites caseWrites;
    @Autowired
    private PlatformTransactionManager transactionManager;


    private final String ip = randomIp();

    private UploadCommand command(FakeParts body) {
        return new UploadCommand(workspace.slug(), pending.token(), new ClientIp(ip, ClientIp.Source.DIRECT), body);
    }

    private UUID caseId() {
        return pending.linkCase().id();
    }

    private LinkCase storedCase() {
        return mongo.findById(caseId(), LinkCase.class);
    }

    private List<Document> documents() {
        return mongo.find(query(where(Document.CASE_ID).is(caseId())), Document.class);
    }

    private List<AuditEvent> audits(TipoAuditoria tipo) {
        return mongo.find(query(where("case_id").is(caseId()).and("tipo").is(tipo)), AuditEvent.class);
    }

    private ItemResultado item(LinkCase linkCase) {
        return linkCase.resultado().itens().stream().filter(i -> i.documentoTipo() == TIPO).findFirst().orElseThrow();
    }

    private double counter(String name, String... tags) {
        Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void assertNothingKeptOrReserved(FakeParts body) {
        assertThat(storage.keys()).isEmpty();
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(documents()).isEmpty();
        LinkCase linkCase = storedCase();
        DocumentosTipo held = linkCase.documentos().get(TIPO);
        assertThat(held == null || held.arquivos() == 0).as("reservation released").isTrue();
        assertThat(linkCase.arquivosTotal() == null || linkCase.arquivosTotal() == 0).isTrue();
        assertThat(body.allDeleted()).as("every part deleted").isTrue();
    }

    private static void assertRefused(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    // --- success -------------------------------------------------------------------------------

    @Test
    void photosAreQuarantinedAndRecordedAsReceivedInOneTransactionWithoutAnyScan() throws Exception {
        byte[] jpeg = TestImages.jpegWithMetadata(64, 48);
        byte[] png = TestImages.pngWithTextChunks(40, 30);
        double accepted = counter(UploadService.METRIC, "resultado", "aceito");
        FakeParts body = FakeParts.of(TIPO.name(), jpeg, png);

        UploadOutcome outcome = service.upload(command(body));

        assertThat(outcome).isEqualTo(new UploadOutcome(TIPO, 2, SituacaoEnvio.verificando));
        List<Document> stored = documents().stream().sorted(Comparator.comparingInt(Document::pagina)).toList();
        assertThat(stored).hasSize(2);
        Document first = stored.getFirst();
        assertThat(first.estado()).isEqualTo(EstadoDocumento.RECEBIDO);
        assertThat(first.pagina()).isEqualTo(1);
        assertThat(stored.get(1).pagina()).isEqualTo(2);
        assertThat(first.formato()).isEqualTo(FormatoEnvio.fotos);
        assertThat(first.contentType()).isEqualTo("image/jpeg");
        assertThat(stored.get(1).contentType()).isEqualTo("image/png");
        assertThat(first.sha256Original()).isEqualTo(sha256(jpeg));
        assertThat(first.bytesOriginal()).isEqualTo(jpeg.length);
        assertThat(first.storageKey()).isNull();
        assertThat(first.quarentenaKey()).isEqualTo(DocumentStorage.key(workspace.id(), caseId(), first.id()));
        assertThat(first.proximaTentativa()).isNotNull();
        assertThat(first.envioId()).isNotNull().isEqualTo(stored.get(1).envioId());
        assertThat(storage.bytes(DocumentStorage.Area.QUARANTINE, first.quarentenaKey())).isEqualTo(jpeg);
        assertThat(storage.keys()).isEmpty();
        assertThat(scanner.calls()).isZero();
        assertThat(sanitizer.calls()).isZero();

        LinkCase linkCase = storedCase();
        assertThat(linkCase.documentos().get(TIPO)).isEqualTo(new DocumentosTipo(FormatoEnvio.fotos, 2,
                jpeg.length + png.length));
        assertThat(item(linkCase).documentosEnviados()).isEqualTo(2);
        assertThat(item(linkCase).situacaoEnvio()).isEqualTo(SituacaoEnvio.verificando);
        assertThat(item(linkCase).formatoEnvio()).isEqualTo(FormatoEnvio.fotos);
        assertThat(mongo.find(query(where(CaseEvent.CASE_ID).is(caseId())), CaseEvent.class)).isEmpty();
        assertThat(audits(TipoAuditoria.DOCUMENTO_ENVIADO)).singleElement().satisfies(audit -> {
            assertThat(audit.ator()).isEqualTo(Ator.vendedor);
            assertThat(audit.clientIp()).isEqualTo(ip);
            assertThat(audit.workspaceId()).isEqualTo(workspace.id());
            ResumoDocumento documento = audit.resumo().documento();
            assertThat(documento.tipo()).isEqualTo(TIPO);
            assertThat(documento.arquivos()).hasSize(2);
            assertThat(documento.arquivos().getFirst().sha256Original()).isEqualTo(sha256(jpeg));
            assertThat(documento.arquivos().getFirst().sha256()).isNull();
            assertThat(documento.arquivos().getFirst().bytesOriginal()).isEqualTo(jpeg.length);
        });
        assertThat(body.allDeleted()).isTrue();
        assertThat(counter(UploadService.METRIC, "resultado", "aceito")).isEqualTo(accepted + 1);
    }

    @Test
    void aPdfIsQuarantinedAsSentAndMarksTheTypeAsPdf() {
        byte[] pdf = TestPdfs.plain();

        assertThat(service.upload(command(FakeParts.of(TIPO.name(), pdf))))
                .isEqualTo(new UploadOutcome(TIPO, 1, SituacaoEnvio.verificando));

        Document document = documents().getFirst();
        assertThat(document.formato()).isEqualTo(FormatoEnvio.pdf);
        assertThat(document.pagina()).isEqualTo(1);
        assertThat(storage.bytes(DocumentStorage.Area.QUARANTINE, document.quarentenaKey())).isEqualTo(pdf);
        assertThat(item(storedCase()).formatoEnvio()).isEqualTo(FormatoEnvio.pdf);
    }

    @Test
    void laterPhotosAddPagesAfterTheOnesStillBeingChecked() {
        service.upload(command(FakeParts.of(TIPO.name(), TestImages.jpeg(10, 10))));

        UploadOutcome second = service.upload(command(FakeParts.of(TIPO.name(), TestImages.jpeg(11, 11))));

        assertThat(second).isEqualTo(new UploadOutcome(TIPO, 2, SituacaoEnvio.verificando));
        assertThat(documents()).extracting(Document::pagina).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void theScanJobThenAcceptsWhatTheUploadReceived() {
        service.upload(command(FakeParts.of(TIPO.name(), TestPdfs.plain())));

        scans.runOnce();

        assertThat(documents()).singleElement().satisfies(d -> assertThat(d.estado()).isEqualTo(EstadoDocumento.ACEITO));
        assertThat(item(storedCase()).situacaoEnvio()).isEqualTo(SituacaoEnvio.aceito);
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(storage.keys()).hasSize(1);
    }

    @Test
    void aNudgeThatFailsAfterTheCommitKeepsTheReceivedFilesForThePoller() {
        ScanDispatcher shutDown = mock(ScanDispatcher.class);
        doThrow(new RejectedExecutionException("shutting down")).when(shutDown).nudge();
        UploadCommit commit = new UploadCommit(storage, caseWrites, mongo, transactionManager, shutDown,
                Clock.systemUTC());
        FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(19, 19));

        try (PreparedUpload prepared = service.prepare(command(body))) {
            assertThat(commit.commit(prepared, new ClientIp(ip, ClientIp.Source.DIRECT)).situacao())
                    .isEqualTo(SituacaoEnvio.verificando);
        }

        assertThat(documents()).singleElement().satisfies(d -> assertThat(d.estado()).isEqualTo(EstadoDocumento.RECEBIDO));
        assertThat(storage.quarantineKeys()).hasSize(1);
        assertThat(storedCase().documentos().get(TIPO).arquivos()).isEqualTo(1);
    }

    // --- duplicates ----------------------------------------------------------------------------

    @Test
    void aFileStillBeingCheckedIsADuplicateThatAddsNothing() {
        byte[] photo = TestImages.jpeg(30, 30);
        service.upload(command(FakeParts.of(TIPO.name(), photo)));

        UploadOutcome retry = service.upload(command(FakeParts.of(TIPO.name(), photo)));

        assertThat(retry).isEqualTo(new UploadOutcome(TIPO, 1, SituacaoEnvio.verificando));
        assertThat(documents()).hasSize(1);
        assertThat(storage.quarantineKeys()).hasSize(1);
    }

    @Test
    void aFileRefusedAsNotAllowedIsRefusedAtOnceWhenSentAgain() {
        byte[] marked = TestPdfs.withScannerMarker();
        service.upload(command(FakeParts.of(TIPO.name(), marked)));
        scans.runOnce();
        FakeParts again = FakeParts.of(TIPO.name(), marked);

        assertRefused(() -> service.upload(command(again)), ErrorCode.FORMATO_INVALIDO);

        assertThat(documents()).singleElement().satisfies(d -> assertThat(d.estado()).isEqualTo(EstadoDocumento.RECUSADO));
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(again.allDeleted()).isTrue();
    }

    @Test
    void aFileThatCouldntBeVerifiedMayBeSentAgain() {
        byte[] pdf = TestPdfs.plain();
        service.upload(command(FakeParts.of(TIPO.name(), pdf)));
        Document first = documents().getFirst();
        mongo.updateFirst(query(where("_id").is(first.id())), new Update()
                .set(Document.ESTADO, EstadoDocumento.RECUSADO).set(Document.MOTIVO_RECUSA, MotivoRecusa.VERIFICACAO_FALHOU)
                .unset(Document.ATIVO), Document.class);
        mongo.updateFirst(query(where("_id").is(caseId())), new Update().unset(LinkCase.DOCUMENTOS + "." + TIPO.name())
                .set(LinkCase.ARQUIVOS_TOTAL, 0), LinkCase.class);

        UploadOutcome again = service.upload(command(FakeParts.of(TIPO.name(), pdf)));

        assertThat(again).isEqualTo(new UploadOutcome(TIPO, 1, SituacaoEnvio.verificando));
        assertThat(documents()).hasSize(2);
    }

    @Test
    void filesStillBeingCheckedCountTowardTheThirtyFileLimit() {
        mongo.updateFirst(query(where("_id").is(caseId())), new Update()
                .set(LinkCase.documentosField(OTHER_TIPO, DocumentosTipo.FORMATO), FormatoEnvio.fotos)
                .set(LinkCase.documentosField(OTHER_TIPO, DocumentosTipo.ARQUIVOS), 29)
                .set(LinkCase.documentosField(OTHER_TIPO, DocumentosTipo.BYTES), 29_000L)
                .set(LinkCase.ARQUIVOS_TOTAL, 29), LinkCase.class);
        FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(10, 10), TestImages.jpeg(11, 11));

        assertRefused(() -> service.upload(command(body)), ErrorCode.LIMITE_ARQUIVOS);

        assertThat(documents()).isEmpty();
        assertThat(storage.quarantineKeys()).isEmpty();
    }

    // --- refusals and failures -----------------------------------------------------------------

    @Test
    void anUnsupportedJpegIsRefusedBeforeAnythingIsReservedAndCounted() {
        double unsupported = counter(ScanWorker.REENCODE_METRIC, "resultado", "unsupported");
        FakeParts body = FakeParts.of(TIPO.name(), ImageHeaders.jpeg(ImageHeaders.SOF0, 100, 100, 8, 4, null));

        assertRefused(() -> service.upload(command(body)), ErrorCode.FORMATO_INVALIDO);

        assertNothingKeptOrReserved(body);
        assertThat(counter(ScanWorker.REENCODE_METRIC, "resultado", "unsupported")).isEqualTo(unsupported + 1);
    }

    @Test
    void aPhotoOverThePixelCapIsRefusedBeforeAnythingIsReserved() {
        FakeParts body = FakeParts.of(TIPO.name(), ImageHeaders.jpeg(10_000, 6_000));

        assertRefused(() -> service.upload(command(body)), ErrorCode.FORMATO_INVALIDO);

        assertNothingKeptOrReserved(body);
    }

    @Test
    void aStorageFailureOnTheSecondOfThreePhotosDeletesTheFirstAndReleasesTheReservation() {
        storage.failPutOnCall(2);
        FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(10, 10), TestImages.jpeg(11, 11),
                TestImages.jpeg(12, 12));

        assertThatThrownBy(() -> service.upload(command(body))).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INDISPONIVEL);
            assertThat(e.headers()).containsEntry("Retry-After", "30");
        });

        assertNothingKeptOrReserved(body);
        assertThat(storage.deletes()).isEqualTo(1);
        assertThat(audits(TipoAuditoria.DOCUMENTO_ENVIADO)).isEmpty();
    }

    // --- races ---------------------------------------------------------------------------------

    @Test
    void twoConcurrentIdenticalUploadsKeepOnePageAndBothAnswer202WithTheSameCount() throws Exception {
        byte[] photo = TestImages.jpeg(20, 20);
        ScriptedScanner.Gate gate = storage.holdPuts();
        CompletableFuture<UploadOutcome> a = CompletableFuture.supplyAsync(
                () -> service.upload(command(FakeParts.of(TIPO.name(), photo))));
        CompletableFuture<UploadOutcome> b = CompletableFuture.supplyAsync(
                () -> service.upload(command(FakeParts.of(TIPO.name(), photo))));
        awaitWaiting(gate, 2);

        gate.release();

        UploadOutcome expected = new UploadOutcome(TIPO, 1, SituacaoEnvio.verificando);
        assertThat(a.get(30, TimeUnit.SECONDS)).isEqualTo(expected);
        assertThat(b.get(30, TimeUnit.SECONDS)).isEqualTo(expected);
        assertThat(documents()).hasSize(1);
        assertThat(storage.quarantineKeys()).hasSize(1);
        assertThat(storedCase().documentos().get(TIPO).arquivos()).isEqualTo(1);
        assertThat(storedCase().arquivosTotal()).isEqualTo(1);
    }

    @Test
    void aTypeWhoseOnlyPdfWasRefusedTakesPhotosAfterwards() {
        service.upload(command(FakeParts.of(TIPO.name(), TestPdfs.withScannerMarker())));
        scans.runOnce();

        UploadOutcome photos = service.upload(command(FakeParts.of(TIPO.name(), TestImages.jpeg(14, 14))));

        assertThat(photos).isEqualTo(new UploadOutcome(TIPO, 1, SituacaoEnvio.verificando));
        assertThat(storedCase().documentos().get(TIPO).formato()).isEqualTo(FormatoEnvio.fotos);
        assertThat(item(storedCase()).formatoEnvio()).isEqualTo(FormatoEnvio.fotos);
    }

    @Test
    void retentionStartingBeforeTheCommitMakesTheUploadNotFoundWithNothingKept() throws Exception {
        assertGoneBeforeTheCommit(new Update()
                .set(LinkCase.TOKEN_REVOGADO_EM, Instant.now())
                .set(LinkCase.RETENCAO_ESTADO, EstadoRetencao.EM_ANDAMENTO));
    }

    @Test
    void aDecisionBeforeTheCommitMakesTheUploadNotFoundWithNothingKept() throws Exception {
        assertGoneBeforeTheCommit(new Update().set(LinkCase.ESTADO, EstadoCaso.approved));
    }

    private void assertGoneBeforeTheCommit(Update meanwhile) throws Exception {
        ScriptedScanner.Gate gate = storage.holdPuts();
        FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(10, 10));
        CompletableFuture<UploadOutcome> upload = CompletableFuture.supplyAsync(() -> service.upload(command(body)));
        assertThat(gate.awaitEntered(Duration.ofSeconds(10))).isTrue();

        mongo.updateFirst(query(where("_id").is(caseId())), meanwhile, LinkCase.class);
        gate.release();

        assertThatThrownBy(() -> upload.get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class)
                .cause().satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.CASO_NAO_ENCONTRADO));
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(documents()).isEmpty();
        assertThat(storedCase().documentos().get(TIPO)).isNull();
        assertThat(body.allDeleted()).isTrue();
    }

    private static void awaitWaiting(ScriptedScanner.Gate gate, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (gate.waiting() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(gate.waiting()).isEqualTo(count);
    }
}
