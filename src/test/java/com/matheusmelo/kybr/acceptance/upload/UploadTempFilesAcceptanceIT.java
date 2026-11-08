package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.acceptance.upload.fixtures.BrowserMultipart;
import com.matheusmelo.kybr.acceptance.upload.fixtures.SampleFiles;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import com.matheusmelo.kybr.support.web.RawHttp;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Multipart parts are written to a temporary directory before the upload reads them. They are whole
 * identity documents, so none may outlive its request, whatever the outcome. Also: a storage
 * failure part-way through keeps nothing, and the same files sent again are accepted in full.
 */
class UploadTempFilesAcceptanceIT extends UploadAcceptanceTest {

    @Test
    void noPartOutlivesAnyOutcome() throws Exception {
        TestCases.Built decided = seedCase(EstadoCaso.approved);
        TestCases.Built fullBytes = seedCaseHolding(Map.of(TIPO, new DocumentosTipo(FormatoEnvio.fotos, 1, TEN_MIB - 10)));
        TestCases.Built fullFiles = seedCaseHolding(Map.of(OTHER_TIPO, new DocumentosTipo(FormatoEnvio.fotos, 30, 900)));
        byte[] bigPhoto = SampleFiles.photoOfExactSize(2 * 1024 * 1024, "grande");

        assertReceived(upload(pending, TIPO, bigPhoto), TIPO, 1);
        assertNoTempFileLeft();
        assertError(upload(pending, OTHER_TIPO, SampleFiles.wrongMagic().get("html")), 415, "FORMATO_INVALIDO");
        assertNoTempFileLeft();
        assertReceived(upload(pending, OTHER_TIPO, TestPdfs.withScannerMarker()), OTHER_TIPO, 1);
        assertNoTempFileLeft();
        assertError(upload(fullBytes, TIPO, bigPhoto), 413, "ARQUIVO_GRANDE");
        assertNoTempFileLeft();
        assertError(upload(fullFiles, TIPO, bigPhoto), 413, "LIMITE_ARQUIVOS");
        assertNoTempFileLeft();
        assertError(upload(decided, TIPO, bigPhoto), 409, "CASO_DECIDIDO");
        assertNoTempFileLeft();
        assertError(upload(uploadPath(workspace.slug(), TestCases.newToken()), TIPO.name(), bigPhoto), 404,
                "CASO_NAO_ENCONTRADO");
        assertNoTempFileLeft();

        storage.failPutOnCall(storage.puts() + 1);
        assertError(upload(pending, OTHER_TIPO, SampleFiles.photo("armazenamento")), 503, "INDISPONIVEL");
        assertNoTempFileLeft();

        storage.throwingOnPut(new IllegalStateException("falha sintetica"));
        assertError(upload(pending, OTHER_TIPO, SampleFiles.photo("excecao")), 500, "ERRO_INTERNO");
        assertNoTempFileLeft();
        storage.reset();

        BrowserMultipart tooManyParts = new BrowserMultipart().field("tipo", TIPO.name());
        for (int i = 0; i < 40; i++) {
            tooManyParts.file("arquivos", i + ".jpg", "image/jpeg", SampleFiles.photo("parte-" + i));
        }
        byte[] body = tooManyParts.body();
        RawHttp.Response refused = RawHttp.send(port, tooManyParts.head(uploadPath(pending), body, null), body);
        assertThat(refused.status()).isBetween(400, 499);
        assertNoTempFileLeft();
    }

    @Test
    void aStorageFailureOnTheSecondOfThreePhotosKeepsNothingAndTheResendIsAcceptedInFull() {
        byte[][] photos = {SampleFiles.photo("1"), SampleFiles.photo("2"), SampleFiles.photo("3")};
        storage.failPutOnCall(2);

        Response failed = upload(pending, TIPO, photos);

        assertError(failed, 503, "INDISPONIVEL");
        assertThat(failed.header("Retry-After")).matches("^[1-9]\\d*$");
        assertThat(storage.keys()).as("the first object is deleted again").isEmpty();
        assertThat(documentsOf(pending.linkCase().id())).isEmpty();
        assertNothingReserved(pending.linkCase().id());
        assertNoTempFileLeft();

        assertReceived(upload(pending, TIPO, photos), TIPO, 3);
        assertThat(storage.keys()).hasSize(3);
    }
}
