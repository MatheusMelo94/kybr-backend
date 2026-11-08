package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.acceptance.upload.fixtures.BrowserMultipart;
import com.matheusmelo.kybr.acceptance.upload.fixtures.SampleFiles;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.service.scan.StubScanner;
import com.matheusmelo.kybr.support.web.RawHttp;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The upload's limits at their exact boundaries: 10 MiB of original bytes per document type across
 * calls, 30 files per case across types, 30 file parts per request with the longest file names a
 * browser sends, and which refusal wins when a request breaks two rules at once.
 */
class UploadLimitsAcceptanceIT extends UploadAcceptanceTest {

    private static final String LONG_NAME_CHAR = "€"; // three bytes in UTF-8

    private static byte[][] photos(int count, String label) {
        byte[][] files = new byte[count][];
        for (int i = 0; i < count; i++) {
            files[i] = SampleFiles.photo(label + "-" + i);
        }
        return files;
    }

    // --- bytes per type ------------------------------------------------------------------------

    @Test
    void aPdfOfExactlyTenMebibytesIsAccepted() {
        Response response = upload(pending, TIPO, SampleFiles.pdfOfExactSize(TEN_MIB));

        assertReceived(response, TIPO, 1);
        assertThat(documentsOf(pending.linkCase().id())).singleElement()
                .satisfies(d -> assertThat(d.bytesOriginal()).isEqualTo(TEN_MIB));
    }

    @Test
    void aPdfOneByteOverTenMebibytesIsRefusedByTheParserAndNothingIsKept() {
        Response response = upload(pending, TIPO, SampleFiles.pdfOfExactSize(TEN_MIB + 1));

        assertError(response, 413, "REQUISICAO_GRANDE");
        assertThat(storage.keys()).isEmpty();
        assertThat(documentsOf(pending.linkCase().id())).isEmpty();
        assertThat(scanner.calls()).isZero();
        assertNoTempFileLeft();
    }

    @Test
    void photosFillATypeAcrossCallsToExactlyTenMebibytesAndOneMoreByteIsRefused() {
        int six = 6 * 1024 * 1024;
        assertReceived(upload(pending, TIPO, SampleFiles.photoOfExactSize(six / 2, "a"),
                SampleFiles.photoOfExactSize(six / 2, "b")), TIPO, 2);

        assertReceived(upload(pending, TIPO, SampleFiles.photoOfExactSize(TEN_MIB - six, "c")), TIPO, 3);

        assertError(upload(pending, TIPO, SampleFiles.photoOfExactSize(SampleFiles.photo("d").length, "d")), 413,
                "ARQUIVO_GRANDE");
        LinkCase stored = stored(pending.linkCase().id());
        assertThat(stored.documentos().get(TIPO).bytes()).isEqualTo(TEN_MIB);
        assertThat(stored.documentos().get(TIPO).arquivos()).isEqualTo(3);
    }

    @Test
    void theLimitCountsTheOriginalBytesNotTheSmallerStoredOnes() {
        byte[] padded = SampleFiles.photoOfExactSize(9 * 1024 * 1024, "acolchoada");
        assertReceived(upload(pending, TIPO, padded), TIPO, 1);
        assertThat(storage.bytes(storage.keys().getFirst()).length).as("the comment padding is stripped")
                .isLessThan(padded.length / 100);

        Response response = upload(pending, TIPO, SampleFiles.photoOfExactSize(1536 * 1024, "mais"));

        assertError(response, 413, "ARQUIVO_GRANDE");
    }

    @Test
    void anotherTypeHasItsOwnTenMebibytes() {
        assertReceived(upload(pending, TIPO, SampleFiles.pdfOfExactSize(TEN_MIB)), TIPO, 1);

        assertReceived(upload(pending, OTHER_TIPO, SampleFiles.pdfOfExactSize(TEN_MIB)), OTHER_TIPO, 1);
    }

    // --- files per case ------------------------------------------------------------------------

    @Test
    void thirtyFilesPerCaseAcrossTypesAndTheRefusalStoresNothing() {
        assertReceived(upload(pending, TIPO, photos(15, "a")), TIPO, 15);
        assertReceived(upload(pending, OTHER_TIPO, photos(14, "b")), OTHER_TIPO, 14);

        assertError(upload(pending, TIPO, photos(2, "c")), 413, "LIMITE_ARQUIVOS");
        assertThat(documentsOf(pending.linkCase().id())).hasSize(29);
        assertThat(storage.keys()).hasSize(29);

        assertReceived(upload(pending, TIPO, photos(1, "d")), TIPO, 16);
        assertError(upload(pending, OTHER_TIPO, photos(1, "e")), 413, "LIMITE_ARQUIVOS");
        assertThat(documentsOf(pending.linkCase().id())).hasSize(30);
    }

    @Test
    void thirtyOneFilesInOneRequestOnAnEmptyCaseIs413LimiteArquivos() {
        Response response = upload(pending, TIPO, photos(31, "x"));

        assertError(response, 413, "LIMITE_ARQUIVOS");
        assertThat(storage.keys()).isEmpty();
        assertNoTempFileLeft();
    }

    // --- request shape ---------------------------------------------------------------------------

    @Test
    void thirtyPhotosWithTwoHundredFiftyFiveCharacterNamesFillingTenMebibytesAreAccepted() throws Exception {
        int each = TEN_MIB / 30;
        BrowserMultipart form = new BrowserMultipart().field("tipo", TIPO.name());
        for (int i = 0; i < 30; i++) {
            String name = String.format("%02d", i) + LONG_NAME_CHAR.repeat(248) + ".jpeg";
            assertThat(name).hasSize(255);
            form.fileWithBothNames("arquivos", name, "image/jpeg", SampleFiles.photoOfExactSize(each, "longa-" + i));
        }
        byte[] body = form.body();
        assertThat(body.length).isGreaterThan(TEN_MIB).isLessThanOrEqualTo(BODY_CAP);

        RawHttp.Response response = RawHttp.send(port, form.head(uploadPath(pending), body, ALLOWED_ORIGIN), body);

        assertThat(response.status()).as(response.body()).isEqualTo(202);
        assertThat(response.body()).contains("\"documentos_enviados\":30");
        assertCors(response);
        assertThat(documentsOf(pending.linkCase().id())).hasSize(30);
        assertNoTempFileLeft();
    }

    @Test
    void morePartsThanTheContainerAllowsAreRefusedWithTheEnvelopeAndLeaveNothing() throws Exception {
        BrowserMultipart form = new BrowserMultipart().field("tipo", TIPO.name());
        for (int i = 0; i < 32; i++) {
            form.file("arquivos", "foto-" + i + ".jpg", "image/jpeg", SampleFiles.photo("demais-" + i));
        }
        byte[] body = form.body();

        RawHttp.Response response = RawHttp.send(port, form.head(uploadPath(pending), body, ALLOWED_ORIGIN), body);

        assertThat(response.status()).as(response.body()).isEqualTo(413);
        assertThat(response.header("Content-Type")).startsWith("application/json");
        assertThat(response.body()).contains("\"code\":\"REQUISICAO_GRANDE\"");
        assertCors(response);
        assertThat(storage.keys()).isEmpty();
        assertNoTempFileLeft();
    }

    // --- which refusal wins ----------------------------------------------------------------------

    @Test
    void anUnknownTokenWinsOverABadFormat() {
        assertError(upload(uploadPath(workspace.slug(), TestCases.newToken()), TIPO.name(),
                SampleFiles.wrongMagic().get("gif")), 404, "CASO_NAO_ENCONTRADO");
    }

    @Test
    void aDecidedCaseWinsOverABadFormat() {
        assertError(upload(seedCase(EstadoCaso.approved), TIPO, SampleFiles.wrongMagic().get("gif")), 409,
                "CASO_DECIDIDO");
    }

    @Test
    void aBadFormatWinsOverTheBytesPerType() {
        TestCases.Built nearlyFull = seedCaseHolding(Map.of(TIPO, new DocumentosTipo(FormatoEnvio.fotos, 1, TEN_MIB - 10)));

        assertError(upload(nearlyFull, TIPO, SampleFiles.wrongMagic().get("gif")), 415, "FORMATO_INVALIDO");
    }

    @Test
    void aSingleFileOverTenMebibytesIsRefusedByTheParserWhateverItsFormat() {
        byte[] bigGif = new byte[TEN_MIB + 1];
        System.arraycopy(SampleFiles.wrongMagic().get("gif"), 0, bigGif, 0, 6);

        assertError(upload(pending, TIPO, bigGif), 413, "REQUISICAO_GRANDE");
        assertNoTempFileLeft();
    }

    @Test
    void aFileRefusedAsNotAllowedSentAgainWithANewFileRefusesTheRequestAndReservesNothing() {
        byte[] refused = SampleFiles.photoWithTrailer("recusada", StubScanner.TEST_MARKER);
        assertReceived(upload(pending, TIPO, refused), TIPO, 1);
        assertThat(documentsOf(pending.linkCase().id())).singleElement()
                .satisfies(d -> assertThat(d.motivoRecusa()).hasToString("CONTEUDO_NAO_PERMITIDO"));
        int scans = scanner.calls();

        assertError(upload(pending, TIPO, refused, SampleFiles.photo("nova")), 415, "FORMATO_INVALIDO");

        assertThat(scanner.calls()).as("refused at once, never scanned again").isEqualTo(scans);
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(documentsOf(pending.linkCase().id())).hasSize(1);
        assertNothingReserved(pending.linkCase().id());
    }

    // --- races -----------------------------------------------------------------------------------

    @Test
    void twoParallelSixMebibyteUploadsToOneTypeGiveOneAcceptanceAndOneRefusal() throws Exception {
        int six = 6 * 1024 * 1024;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> a = pool.submit(() -> upload(pending, TIPO, SampleFiles.photoOfExactSize(six, "a")));
            Future<Response> b = pool.submit(() -> upload(pending, TIPO, SampleFiles.photoOfExactSize(six, "b")));
            List<Integer> statuses = new ArrayList<>(List.of(a.get(30, TimeUnit.SECONDS).statusCode(),
                    b.get(30, TimeUnit.SECONDS).statusCode()));

            assertThat(statuses).containsExactlyInAnyOrder(202, 413);
        } finally {
            pool.shutdownNow();
        }
        LinkCase stored = stored(pending.linkCase().id());
        assertThat(stored.documentos().get(TIPO).arquivos()).isEqualTo(1);
        assertThat(documentsOf(pending.linkCase().id())).hasSize(1);
        assertThat(storage.keys()).hasSize(1);
    }

    @Test
    void aPdfAndPhotosInParallelToAnEmptyTypeGiveOneAcceptanceAndOneFormatRefusal() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> a = pool.submit(() -> upload(pending, TIPO, SampleFiles.pdf("paralelo")));
            Future<Response> b = pool.submit(() -> upload(pending, TIPO, photos(2, "paralelo")));
            List<Integer> statuses = List.of(a.get(30, TimeUnit.SECONDS).statusCode(),
                    b.get(30, TimeUnit.SECONDS).statusCode());

            assertThat(statuses).containsExactlyInAnyOrder(202, 415);
        } finally {
            pool.shutdownNow();
        }
        assertThat(documentsOf(pending.linkCase().id())).extracting(Document::formato).containsOnly(
                documentsOf(pending.linkCase().id()).getFirst().formato());
    }
}
