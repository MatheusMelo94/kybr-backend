package com.matheusmelo.kybr.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.acceptance.link.FrontendContract;
import com.matheusmelo.kybr.service.image.SanitizeResult;
import com.matheusmelo.kybr.service.upload.scan.ScanDispatcher;
import com.matheusmelo.kybr.support.upload.TestImages;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import com.matheusmelo.kybr.support.upload.UploadWebIntegrationTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The case read (E04) shows, per document item, how its files were sent, how many count, and their
 * verification: {@code verificando}, then {@code aceito} or {@code recusado} with a generic reason.
 */
class CaseReadAfterUploadIT extends UploadWebIntegrationTest {

    @Autowired
    private ScanDispatcher scans;

    private Map<String, Object> item(String tipo) {
        String raw = http().header("X-Kybr-Client-IP", "203.0.113.9")
                .get("/public/v/" + workspace.slug() + "/cases/" + pending.token()).then().statusCode(200)
                .extract().asString();
        assertThat(FrontendContract.check(FrontendContract.Route.CASE, 200, raw))
                .as("a body the link frontend's parser would reject").isEmpty();
        JsonPath body = JsonPath.from(raw);
        List<Map<String, Object>> itens = body.getList("resultado.itens");
        return itens.stream().filter(item -> tipo.equals(item.get("documento_tipo"))).findFirst().orElseThrow();
    }

    private void upload(String tipo, byte[]... files) {
        var request = httpWithoutKey().multiPart("tipo", tipo);
        for (byte[] file : files) {
            request = request.multiPart("arquivos", "arquivo.bin", file, "application/octet-stream");
        }
        request.post("/public/v/" + workspace.slug() + "/cases/" + pending.token() + "/documents").then()
                .statusCode(202);
    }

    @Test
    void theFormatAndVerificationAreAbsentBeforeAnyUpload() {
        assertThat(item(TIPO.name())).doesNotContainKeys("formato_envio", "situacao_envio", "documentos_recusados",
                "motivo_recusa").containsEntry("documentos_enviados", 0);
    }

    @Test
    void receivedFilesAreVerificandoAndCountUntilTheScanAcceptsThem() {
        upload(OTHER_TIPO.name(), TestImages.jpeg(10, 10), TestImages.jpeg(11, 11));

        assertThat(item(OTHER_TIPO.name())).containsEntry("situacao_envio", "verificando")
                .containsEntry("documentos_enviados", 2).containsEntry("documentos_recusados", 0)
                .containsEntry("formato_envio", "fotos").doesNotContainKey("motivo_recusa");

        scans.runOnce();

        assertThat(item(OTHER_TIPO.name())).containsEntry("situacao_envio", "aceito")
                .containsEntry("documentos_enviados", 2).containsEntry("documentos_recusados", 0);
    }

    @Test
    void oneAcceptedAndOneStillBeingCheckedIsVerificando() {
        upload(OTHER_TIPO.name(), TestImages.jpeg(10, 10));
        scans.runOnce();
        upload(OTHER_TIPO.name(), TestImages.jpeg(12, 12));

        assertThat(item(OTHER_TIPO.name())).containsEntry("situacao_envio", "verificando")
                .containsEntry("documentos_enviados", 2);
    }

    @Test
    void aRefusedLatestUploadIsRecusadoWithAGenericReasonAndDoesntCount() {
        upload(OTHER_TIPO.name(), TestImages.jpeg(10, 10));
        scans.runOnce();
        scanner.infectedOnCall(scanner.calls() + 1, "Synthetic.Signature");
        upload(OTHER_TIPO.name(), TestImages.jpeg(13, 13));
        scans.runOnce();

        Map<String, Object> item = item(OTHER_TIPO.name());
        assertThat(item).containsEntry("situacao_envio", "recusado")
                .containsEntry("motivo_recusa", "CONTEUDO_NAO_PERMITIDO").containsEntry("documentos_enviados", 1)
                .containsEntry("documentos_recusados", 1).containsEntry("formato_envio", "fotos");
        assertThat(item.toString()).doesNotContainIgnoringCase("malware").doesNotContain("Synthetic");
    }

    @Test
    void aPdfMarksItsItemAsPdf() {
        upload(TIPO.name(), TestPdfs.plain());

        assertThat(item(TIPO.name())).containsEntry("formato_envio", "pdf").containsEntry("documentos_enviados", 1);
        assertThat(item(OTHER_TIPO.name())).doesNotContainKey("formato_envio");
    }

    @Test
    void refusalsStayCountedAfterALaterCleanUploadWhichClearsTheReason() {
        scanner.infectedOnCall(1, "Synthetic.Signature");
        upload(OTHER_TIPO.name(), TestImages.jpeg(15, 15));
        scans.runOnce();
        assertThat(item(OTHER_TIPO.name())).containsEntry("situacao_envio", "recusado")
                .doesNotContainKey("formato_envio");

        upload(OTHER_TIPO.name(), TestImages.jpeg(16, 16));
        scans.runOnce();

        assertThat(item(OTHER_TIPO.name())).containsEntry("situacao_envio", "aceito")
                .containsEntry("documentos_enviados", 1).containsEntry("documentos_recusados", 1)
                .doesNotContainKey("motivo_recusa");
    }

    @Test
    void anUnreadablePhotoShowsItsOwnReason() {
        sanitizer.answering(new SanitizeResult.Refused());
        upload(OTHER_TIPO.name(), TestImages.jpeg(17, 17));
        scans.runOnce();

        assertThat(item(OTHER_TIPO.name())).containsEntry("situacao_envio", "recusado")
                .containsEntry("motivo_recusa", "ARQUIVO_INVALIDO");
    }

    @Test
    void photosMarkTheirItemAsPhotosWithTheStoredCount() {
        upload(OTHER_TIPO.name(), TestImages.jpeg(10, 10), TestImages.jpeg(11, 11));
        upload(OTHER_TIPO.name(), TestImages.jpeg(10, 10)); // a retry adds nothing

        assertThat(item(OTHER_TIPO.name())).containsEntry("formato_envio", "fotos")
                .containsEntry("documentos_enviados", 2);
    }
}
