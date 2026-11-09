package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.acceptance.upload.fixtures.SampleFiles;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.service.scan.StubScanner;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the seller sees on the case read while files are verified, per document item: nothing before
 * any upload; {@code verificando} while any file of the type waits; {@code aceito} once all are
 * decided and the latest upload was clean; {@code recusado} with a reason when the latest upload had a
 * refused file; the files still counting and those refused so far. Files still waiting count toward
 * the limits and the duplicate guard exactly like accepted ones.
 */
class VerificationStateAcceptanceIT extends ScanJobAcceptanceTest {

    private static final String[] STATE_FIELDS = {"situacao_envio", "documentos_recusados", "motivo_recusa",
        "formato_envio"};

    private UUID caseId() {
        return pending.linkCase().id();
    }

    private static byte[] marked(String label) {
        return SampleFiles.photoWithTrailer(label, StubScanner.TEST_MARKER);
    }

    // --- the item's state ------------------------------------------------------------------------------

    @Test
    void beforeAnyUploadTheItemCarriesNoVerificationState() {
        assertThat(item(pending, TIPO)).doesNotContainKeys(STATE_FIELDS).containsEntry("documentos_enviados", 0);
    }

    @Test
    void waitingFilesShowVerifyingThenAcceptedOnceVerified() {
        assertReceived(receive(pending, TIPO, SampleFiles.photo("a"), SampleFiles.photo("b")), TIPO, 2);

        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "verificando")
                .containsEntry("documentos_enviados", 2).containsEntry("documentos_recusados", 0)
                .doesNotContainKey("motivo_recusa");

        scanAll();

        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "aceito").containsEntry("documentos_enviados", 2)
                .containsEntry("formato_envio", "fotos").doesNotContainKey("motivo_recusa");
    }

    @Test
    void aLaterUploadStillWaitingMakesAnAcceptedItemVerifyingAgain() {
        assertReceived(scanned(receive(pending, TIPO, SampleFiles.photo("a"))), TIPO, 1);

        assertReceived(receive(pending, TIPO, SampleFiles.photo("b")), TIPO, 2);

        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "verificando")
                .containsEntry("documentos_enviados", 2);
    }

    @Test
    void aRefusedFileInTheLatestUploadShowsRefusedWithTheReasonAndKeepsEarlierAcceptedFiles() {
        assertReceived(scanned(receive(pending, TIPO, SampleFiles.photo("boa"))), TIPO, 1);

        assertReceived(scanned(receive(pending, TIPO, marked("ruim"))), TIPO, 2);

        Map<String, Object> item = item(pending, TIPO);
        assertThat(item).containsEntry("situacao_envio", "recusado").containsEntry("motivo_recusa", "CONTEUDO_NAO_PERMITIDO")
                .containsEntry("documentos_enviados", 1).containsEntry("documentos_recusados", 1);
        String read = readCase(workspace.slug(), pending.token()).asString();
        assertThat(read).doesNotContainIgnoringCase("malware").doesNotContainIgnoringCase("virus")
                .doesNotContainIgnoringCase("infect").doesNotContain("Kybr.Test").doesNotContain(caseId() + "/");
    }

    @Test
    void theRefusedCountIsCumulativeAndKeptAfterALaterCleanUpload() {
        assertReceived(scanned(receive(pending, TIPO, marked("ruim"))), TIPO, 1);
        assertThat(item(pending, TIPO)).containsEntry("documentos_recusados", 1);

        assertReceived(receive(pending, TIPO, SampleFiles.photo("boa")), TIPO, 1);
        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "verificando").doesNotContainKey("motivo_recusa");
        scanAll();

        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "aceito").doesNotContainKey("motivo_recusa")
                .containsEntry("documentos_enviados", 1).containsEntry("documentos_recusados", 1);
        assertReceived(scanned(receive(pending, TIPO, marked("outra-ruim"))), TIPO, 2);
        assertThat(item(pending, TIPO)).containsEntry("documentos_recusados", 2);
    }

    @Test
    void theUploadFormatIsShownWhileTheFirstFileIsStillVerifying() {
        assertReceived(receive(pending, TIPO, SampleFiles.pdf("esperando")), TIPO, 1);

        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "verificando")
                .containsEntry("formato_envio", "pdf");
    }

    @Test
    void aTypeWhoseOnlyPdfWasRefusedAcceptsPhotosAndShowsNoFormat() {
        assertReceived(scanned(receive(pending, TIPO, TestPdfs.withScannerMarker())), TIPO, 1);
        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "recusado").containsEntry("documentos_enviados", 0)
                .doesNotContainKey("formato_envio");

        assertReceived(scanned(receive(pending, TIPO, SampleFiles.photo("depois"))), TIPO, 1);

        assertThat(item(pending, TIPO)).containsEntry("situacao_envio", "aceito").containsEntry("formato_envio", "fotos");
    }

    // --- waiting files count -----------------------------------------------------------------------------

    @Test
    void waitingBytesCountTowardTheTypeLimitAndVerifyingChangesNothing() {
        int six = 6 * 1024 * 1024;
        assertReceived(receive(pending, TIPO, SampleFiles.photoOfExactSize(six, "seis")), TIPO, 1);
        assertReceived(receive(pending, TIPO, SampleFiles.photoOfExactSize(TEN_MIB - six, "quatro")), TIPO, 2);

        assertError(receive(pending, TIPO, SampleFiles.photo("mais")), 413, "ARQUIVO_GRANDE");

        scanAll();
        LinkCase stored = stored(caseId());
        assertThat(documentsOf(caseId())).extracting(Document::estado).containsOnly(EstadoDocumento.ACEITO);
        assertThat(stored.documentos().get(TIPO).bytes()).isEqualTo(TEN_MIB);
        assertThat(stored.documentos().get(TIPO).arquivos()).isEqualTo(2);
    }

    @Test
    void waitingFilesCountTowardThirtyAndARefusalMakesRoom() {
        byte[][] first = new byte[28][];
        for (int i = 0; i < first.length; i++) {
            first[i] = SampleFiles.photo("espera-" + i);
        }
        assertReceived(receive(pending, TIPO, first), TIPO, 28);
        assertReceived(receive(pending, OTHER_TIPO, marked("vai-ser-recusada")), OTHER_TIPO, 1);

        assertError(receive(pending, TIPO, SampleFiles.photo("x"), SampleFiles.photo("y")), 413, "LIMITE_ARQUIVOS");

        scanAll();
        assertReceived(receive(pending, TIPO, SampleFiles.photo("x"), SampleFiles.photo("y")), TIPO, 30);
        assertError(receive(pending, OTHER_TIPO, SampleFiles.photo("z")), 413, "LIMITE_ARQUIVOS");
    }

    @Test
    void aWaitingFileSentAgainIsADuplicateAndSoIsAnAcceptedOne() {
        byte[] pdf = SampleFiles.pdf("repetido");
        assertReceived(receive(pending, TIPO, pdf), TIPO, 1);

        assertReceived(receive(pending, TIPO, pdf), TIPO, 1);
        scanAll();
        assertReceived(receive(pending, TIPO, pdf), TIPO, 1, "aceito");

        assertThat(documentsOf(caseId())).hasSize(1);
        assertThat(scanner.calls()).isEqualTo(1);
    }

    @Test
    void aPdfWhileAnotherPdfOrPhotosWaitBreaksTheFormatRules() {
        assertReceived(receive(pending, TIPO, SampleFiles.pdf("primeiro")), TIPO, 1);
        assertReceived(receive(pending, OTHER_TIPO, SampleFiles.photo("foto")), OTHER_TIPO, 1);

        assertError(receive(pending, TIPO, SampleFiles.pdf("segundo")), 415, "FORMATO_INVALIDO");
        assertError(receive(pending, TIPO, SampleFiles.photo("mistura")), 415, "FORMATO_INVALIDO");
        assertError(receive(pending, OTHER_TIPO, SampleFiles.pdf("mistura")), 415, "FORMATO_INVALIDO");
        assertThat(documentsOf(caseId())).hasSize(2);
    }
}
