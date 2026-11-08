package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.acceptance.upload.fixtures.SampleFiles;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.upload.ImageHeaders;
import com.matheusmelo.kybr.support.upload.TestFiles;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the upload accepts: files typed by their first bytes only (never by the part type or the
 * name), one PDF or photos per document type, only the types the case asked for, and nothing a
 * scanner flags. Every refusal stores nothing and leaves the counters as they were. A file the
 * scanner flags gets exactly the same answer as any other format refusal.
 */
class UploadFormatAcceptanceIT extends UploadAcceptanceTest {

    private void assertNothingKept(TestCases.Built linkCase) {
        assertThat(storage.keys()).isEmpty();
        assertThat(documentsOf(linkCase.linkCase().id())).isEmpty();
        assertNothingReserved(linkCase.linkCase().id());
        assertNoTempFileLeft();
    }

    // --- typing by first bytes -------------------------------------------------------------------

    @Test
    void everyFileThatIsNotAPdfJpegOrPngIs415AndNothingIsKept() {
        for (Map.Entry<String, byte[]> file : SampleFiles.wrongMagic().entrySet()) {
            Response response = upload(pending, TIPO, file.getValue());

            assertThat(response.statusCode()).as(file.getKey()).isEqualTo(415);
            assertError(response, 415, "FORMATO_INVALIDO");
        }
        assertThat(scanner.calls()).isZero();
        assertNothingKept(pending);
    }

    @Test
    void theRawAntivirusTestFileIsRefusedByItsFirstBytesBeforeAnyScan() {
        assertError(upload(pending, TIPO, TestFiles.eicar()), 415, "FORMATO_INVALIDO");

        assertThat(scanner.calls()).isZero();
        assertNothingKept(pending);
    }

    @Test
    void aPdfLabelledAsAPngIsStoredAsAPdfAndAPhotoNamedPdfAsAPhoto() {
        Response pdf = httpWithoutKey().multiPart("tipo", TIPO.name())
                .multiPart("arquivos", "foto.png", SampleFiles.pdf("rotulada"), "image/png").post(uploadPath(pending));
        Response photo = httpWithoutKey().multiPart("tipo", OTHER_TIPO.name())
                .multiPart("arquivos", "contrato.pdf", SampleFiles.photo("rotulada"), "application/pdf")
                .post(uploadPath(pending));

        assertReceived(pdf, TIPO, 1);
        assertReceived(photo, OTHER_TIPO, 1);
        assertThat(documentsOf(pending.linkCase().id())).extracting(d -> d.tipo() + ":" + d.formato() + ":" + d.contentType())
                .containsExactlyInAnyOrder(TIPO + ":pdf:application/pdf", OTHER_TIPO + ":fotos:image/jpeg");
    }

    @Test
    void jpegAndPngPagesMixInOneType() {
        assertReceived(upload(pending, TIPO, SampleFiles.photo("jpeg"), SampleFiles.png(12, 9)), TIPO, 2);
    }

    // --- one PDF or photos per type --------------------------------------------------------------

    @Test
    void twoPdfsInOneRequestAre415() {
        assertError(upload(pending, TIPO, SampleFiles.pdf("um"), SampleFiles.pdf("dois")), 415, "FORMATO_INVALIDO");
        assertNothingKept(pending);
    }

    @Test
    void aPdfMixedWithAPhotoIs415() {
        assertError(upload(pending, TIPO, SampleFiles.pdf("um"), SampleFiles.photo("um")), 415, "FORMATO_INVALIDO");
        assertNothingKept(pending);
    }

    @Test
    void aPdfAddedToATypeHoldingPhotosIs415AndTheCountStays() {
        assertReceived(upload(pending, TIPO, SampleFiles.photo("primeira")), TIPO, 1);

        assertError(upload(pending, TIPO, SampleFiles.pdf("depois")), 415, "FORMATO_INVALIDO");
        assertThat(documentsOf(pending.linkCase().id())).hasSize(1);
    }

    @Test
    void photosOrASecondPdfAddedToATypeHoldingAPdfAre415() {
        assertReceived(upload(pending, TIPO, SampleFiles.pdf("primeiro")), TIPO, 1);

        assertError(upload(pending, TIPO, SampleFiles.photo("depois")), 415, "FORMATO_INVALIDO");
        assertError(upload(pending, TIPO, SampleFiles.pdf("segundo")), 415, "FORMATO_INVALIDO");
        assertThat(documentsOf(pending.linkCase().id())).hasSize(1);
    }

    // --- the parts -------------------------------------------------------------------------------

    @Test
    void aMissingUnknownOrLowercaseTypeOrNoFilesAre415() {
        byte[] photo = SampleFiles.photo("partes");
        List<Response> responses = List.of(
                upload(uploadPath(pending), null, photo),
                upload(uploadPath(pending), "OUTRO", photo),
                upload(uploadPath(pending), "responsavel", photo),
                upload(uploadPath(pending), TIPO.name()),
                httpWithoutKey().multiPart("tipo", TIPO.name()).multiPart("arquivo", "a.jpg", photo, "image/jpeg")
                        .post(uploadPath(pending)),
                httpWithoutKey().multiPart("tipo", TIPO.name()).multiPart("arquivos", "texto").post(uploadPath(pending)));
        for (Response response : responses) {
            assertError(response, 415, "FORMATO_INVALIDO");
        }
        assertNothingKept(pending);
    }

    @Test
    void twoDifferentTypePartsAre415() {
        Response response = httpWithoutKey().multiPart("tipo", TIPO.name()).multiPart("tipo", OTHER_TIPO.name())
                .multiPart("arquivos", "a.jpg", SampleFiles.photo("dois-tipos"), "image/jpeg").post(uploadPath(pending));

        assertError(response, 415, "FORMATO_INVALIDO");
        assertNothingKept(pending);
    }

    // --- what the case allows --------------------------------------------------------------------

    @Test
    void aTypeTheCaseDidNotAskForIs415() {
        assertError(upload(pending, DocumentoTipo.CARTAO_CNPJ, SampleFiles.photo("nao-pedido")), 415, "FORMATO_INVALIDO");
        assertNothingKept(pending);
    }

    @Test
    void aCaseStillProcessingOrWithoutDocumentItemsAcceptsNothing() {
        TestCases.Built processing = seedCase(EstadoCaso.processing);
        TestCases.Built noItems = seedCaseWith(EstadoCaso.pending, List.of());
        TestCases.Built recentOnly = seedCaseWith(EstadoCaso.pending,
                List.of(new ItemResultado(CodigoResultado.EMPRESA_RECENTE, null, null, 30, null, null, null)));

        for (TestCases.Built linkCase : List.of(processing, noItems, recentOnly)) {
            assertError(upload(linkCase, TIPO, SampleFiles.photo("sem-pedido")), 415, "FORMATO_INVALIDO");
        }
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void approvedAndBlockedCasesAre409() {
        assertError(upload(seedCase(EstadoCaso.approved), TIPO, SampleFiles.photo("x")), 409, "CASO_DECIDIDO");
        assertError(upload(seedCase(EstadoCaso.blocked), TIPO, SampleFiles.photo("x")), 409, "CASO_DECIDIDO");
    }

    // --- the scanner -----------------------------------------------------------------------------

    @Test
    void aPdfTheScannerFlagsIsReceivedThenRefusedGenericallyOnTheCaseReadAndAudited() {
        Response flagged = upload(pending, TIPO, TestPdfs.withScannerMarker());

        assertReceived(flagged, TIPO, 1);
        scanAll();

        Response caseRead = readCase(workspace.slug(), pending.token());
        assertThat(caseRead.statusCode()).isEqualTo(200);
        Map<String, Object> item = caseRead.jsonPath().getMap("resultado.itens.find { it.documento_tipo == '"
                + TIPO.name() + "' }");
        assertThat(item).containsEntry("situacao_envio", "recusado")
                .containsEntry("motivo_recusa", "CONTEUDO_NAO_PERMITIDO").containsEntry("documentos_enviados", 0)
                .containsEntry("documentos_recusados", 1);
        assertThat(caseRead.asString()).doesNotContainIgnoringCase("malware").doesNotContainIgnoringCase("virus");
        assertThat(scanner.calls()).isEqualTo(1);
        assertThat(auditsOf(pending.linkCase().id())).extracting(a -> a.tipo())
                .contains(TipoAuditoria.DOCUMENTO_RECUSADO);
        assertThat(storage.keys()).isEmpty();
        assertThat(storage.quarantineKeys()).isEmpty();
        assertNothingReserved(pending.linkCase().id());
    }

    @Test
    void aFlaggedFileReleasesItsReservationSoTheFullTenMebibytesStillFit() {
        assertReceived(upload(pending, TIPO, TestPdfs.withScannerMarker()), TIPO, 1);
        assertNothingReserved(pending.linkCase().id());

        assertReceived(upload(pending, TIPO, SampleFiles.pdfOfExactSize(TEN_MIB)), TIPO, 1);
    }

    @Test
    void oneFlaggedPhotoAmongThreeIsRefusedAloneAndTheOthersAreKept() {
        byte[] flagged = SampleFiles.photoWithTrailer("segunda", com.matheusmelo.kybr.service.scan.StubScanner.TEST_MARKER);

        Response response = upload(pending, TIPO, SampleFiles.photo("primeira"), flagged, SampleFiles.photo("terceira"));

        assertReceived(response, TIPO, 3);
        assertThat(storage.keys()).hasSize(2);
        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(stored(pending.linkCase().id()).arquivosTotal()).isEqualTo(2);
    }

    @Test
    void anUnavailableScannerKeepsTheFileWaitingWithItsReservation() {
        scanner.unavailable();

        assertReceived(upload(pending, TIPO, SampleFiles.pdfOfExactSize(TEN_MIB)), TIPO, 1);

        assertThat(storage.quarantineKeys()).hasSize(1);
        assertThat(storage.keys()).isEmpty();
        assertThat(stored(pending.linkCase().id()).documentos().get(TIPO).bytes()).isEqualTo(TEN_MIB);
    }

    // --- photo headers, checked without decoding -------------------------------------------------

    @Test
    void photosClaimingHugeDimensionsAre415() {
        List<byte[]> bombs = List.of(ImageHeaders.png(20_000, 20_000), ImageHeaders.jpeg(65_535, 65_535),
                ImageHeaders.jpeg(12_001, 10), ImageHeaders.jpeg(10_000, 5_001));
        for (byte[] bomb : bombs) {
            assertError(upload(pending, TIPO, bomb), 415, "FORMATO_INVALIDO");
        }
        assertNothingKept(pending);
    }

    @Test
    void jpegEncodingsThePhotoCleanerCannotReadAre415() {
        List<byte[]> unsupported = List.of(
                ImageHeaders.jpeg(ImageHeaders.SOF0, 100, 100, 8, 4, null), // CMYK
                ImageHeaders.jpeg(ImageHeaders.SOF0, 100, 100, 8, 3, 2), // YCCK
                ImageHeaders.jpeg(0xC1, 100, 100, 12, 3, null), // 12-bit
                ImageHeaders.jpeg(0xC9, 100, 100, 8, 3, null)); // arithmetic-coded
        for (byte[] photo : unsupported) {
            assertError(upload(pending, TIPO, photo), 415, "FORMATO_INVALIDO");
        }
        assertNothingKept(pending);
    }

    @Test
    void aCorruptPhotoIs415AndReleasesItsReservation() {
        byte[] whole = SampleFiles.photo("cortada");
        assertError(upload(pending, TIPO, ImageHeaders.truncated(whole, 40)), 415, "FORMATO_INVALIDO");
        assertNothingKept(pending);

        assertReceived(upload(pending, TIPO, whole), TIPO, 1);
        assertThat(stored(pending.linkCase().id()).documentos().get(TIPO).formato()).isEqualTo(FormatoEnvio.fotos);
    }
}
