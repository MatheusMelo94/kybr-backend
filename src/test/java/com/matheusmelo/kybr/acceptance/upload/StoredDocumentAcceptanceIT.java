package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.acceptance.upload.fixtures.SampleFiles;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.CaseEvent;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.support.upload.TestImages;
import io.restassured.response.Response;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What an accepted upload leaves behind: stored photos without their metadata or trailing data, a
 * stored PDF byte for byte, one record per page with both hashes and sizes, storage keys and
 * metadata that carry no token or file name, the audit and case event, and the case read's per-item
 * count and upload format.
 */
class StoredDocumentAcceptanceIT extends UploadAcceptanceTest {

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private byte[] onlyStoredObject() {
        assertThat(storage.keys()).hasSize(1);
        return storage.bytes(storage.keys().getFirst());
    }

    // --- photo metadata --------------------------------------------------------------------------

    @Test
    void aStoredJpegHasNoExifGpsMakerNoteXmpOrComment() {
        byte[] photo = SampleFiles.photo("comentario-sintetico");
        byte[] withMetadata = TestImages.withJpegMetadata(TestImages.jpeg(40, 30));
        assertThat(TestImages.hasGps(withMetadata)).isTrue();

        assertReceived(upload(pending, TIPO, withMetadata), TIPO, 1);
        byte[] stored = onlyStoredObject();

        assertThat(TestImages.hasExif(stored)).isFalse();
        assertThat(TestImages.hasGps(stored)).isFalse();
        assertThat(TestImages.hasMakerNote(stored)).isFalse();
        assertThat(TestImages.hasXmp(stored)).isFalse();
        assertThat(TestImages.containsBytes(stored, TestImages.GPS_SENTINEL)).isFalse();
        assertThat(TestImages.containsBytes(stored, "Exif\0\0")).isFalse();
        assertThat(TestImages.containsBytes(stored, "http://ns.adobe.com/xap")).isFalse();
        assertThat(TestImages.jpegMarkers(stored)).doesNotContain(0xE1, 0xE2, 0xED, 0xFE);

        storage.reset();
        assertReceived(upload(pending, OTHER_TIPO, photo), OTHER_TIPO, 1);
        assertThat(TestImages.containsBytes(onlyStoredObject(), "comentario-sintetico")).isFalse();
    }

    @Test
    void aStoredPngHasOnlyItsCriticalChunks() {
        assertReceived(upload(pending, TIPO, TestImages.pngWithTextChunks(20, 20)), TIPO, 1);

        assertThat(TestImages.pngChunkTypes(onlyStoredObject())).isSubsetOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND")
                .contains("IHDR", "IDAT", "IEND");
    }

    @Test
    void dataAfterTheEndOfAJpegIsNotStored() {
        String trailer = "<html>CANARIO-RABO</html>%PDF-1.7";

        assertReceived(upload(pending, TIPO, SampleFiles.photoWithTrailer("rabo", trailer)), TIPO, 1);

        byte[] stored = onlyStoredObject();
        assertThat(TestImages.containsBytes(stored, "CANARIO-RABO")).isFalse();
        assertThat(TestImages.containsBytes(stored, "%PDF")).isFalse();
    }

    // --- records -----------------------------------------------------------------------------------

    @Test
    void aStoredPdfIsByteIdenticalAndRecordedWithBothHashesAndNoTokenOrNameInItsKey() throws Exception {
        byte[] pdf = SampleFiles.pdf("identico");

        Response response = httpWithoutKey().multiPart("tipo", TIPO.name())
                .multiPart("arquivos", "contrato-assinado.pdf", pdf, "application/pdf").post(uploadPath(pending));

        assertReceived(scanned(response), TIPO, 1);
        String key = storage.keys().getFirst();
        assertThat(storage.bytes(key)).isEqualTo(pdf);
        assertThat(storage.contentType(key)).isEqualTo("application/pdf");
        assertThat(storage.metadata(key).toString()).doesNotContain("contrato").doesNotContainIgnoringCase("disposition");
        Document document = documentsOf(pending.linkCase().id()).getFirst();
        assertThat(key).matches(workspace.id() + "/" + pending.linkCase().id() + "/" + document.id()
                + "/[0-9a-f-]{36}").doesNotContain(pending.token()); // the document key plus the scan attempt
        assertThat(document.storageKey()).isEqualTo(key);
        assertThat(document.tipo()).isEqualTo(TIPO);
        assertThat(document.pagina()).isEqualTo(1);
        assertThat(document.contentType()).isEqualTo("application/pdf");
        assertThat(document.bytesOriginal()).isEqualTo(pdf.length);
        assertThat(document.bytesArmazenados()).isEqualTo(pdf.length);
        assertThat(document.sha256Original()).isEqualTo(sha256(pdf));
        assertThat(document.sha256()).isEqualTo(sha256(pdf));
    }

    @Test
    void photosAreNumberedPagesWithTheOriginalAndStoredSizesAndHashes() throws Exception {
        byte[] first = TestImages.withJpegMetadata(TestImages.jpeg(30, 20));
        byte[] second = SampleFiles.photo("segunda");
        assertReceived(upload(pending, TIPO, first), TIPO, 1);

        assertReceived(upload(pending, TIPO, second), TIPO, 2);

        List<Document> pages = documentsOf(pending.linkCase().id()).stream()
                .sorted(java.util.Comparator.comparingInt(Document::pagina)).toList();
        assertThat(pages).extracting(Document::pagina).containsExactly(1, 2);
        Document page = pages.getFirst();
        assertThat(page.sha256Original()).isEqualTo(sha256(first));
        assertThat(page.bytesOriginal()).isEqualTo(first.length);
        byte[] stored = storage.bytes(page.storageKey());
        assertThat(page.sha256()).isEqualTo(sha256(stored)).isNotEqualTo(page.sha256Original());
        assertThat(page.bytesArmazenados()).isEqualTo(stored.length).isLessThan(first.length);
        assertThat(page.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void anAcceptedUploadIsAuditedWithTheSocketAddressAndRecordedAsACaseEvent() {
        Response response = httpWithoutKey().header(CLIENT_IP_HEADER, "203.0.113.77").header("X-Forwarded-For", "203.0.113.78")
                .multiPart("tipo", TIPO.name()).multiPart("arquivos", "a.jpg", SampleFiles.photo("auditada"), "image/jpeg")
                .post(uploadPath(pending));

        assertReceived(response, TIPO, 1);
        scanAll();
        List<AuditEvent> received = auditsOf(pending.linkCase().id()).stream()
                .filter(a -> a.tipo() == TipoAuditoria.DOCUMENTO_ENVIADO).toList();
        assertThat(received).singleElement().satisfies(audit -> {
            assertThat(audit.clientIp()).isNotIn("203.0.113.77", "203.0.113.78");
            assertThat(audit.resumo().documento().tipo()).isEqualTo(TIPO);
            assertThat(audit.resumo().documento().arquivos()).singleElement()
                    .satisfies(file -> assertThat(file.sha256Original()).isNotBlank());
        });
        assertThat(mongo.find(query(where("case_id").is(pending.linkCase().id())), CaseEvent.class))
                .anySatisfy(event -> assertThat(event.dados().tipo()).isEqualTo(TIPO));
    }

    // --- the case read -----------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> itemFor(String tipo) {
        Response read = readCase(workspace.slug(), pending.token());
        assertThat(read.statusCode()).as(read.asString()).isEqualTo(200);
        List<Map<String, Object>> items = read.jsonPath().getList("resultado.itens");
        return items.stream().filter(i -> tipo.equals(i.get("documento_tipo"))).findFirst().orElseThrow();
    }

    @Test
    void theCaseReadShowsTheCountAndTheUploadFormatOnlyOnceAFileWasAccepted() {
        assertThat(itemFor(TIPO.name())).doesNotContainKey("formato_envio").containsEntry("documentos_enviados", 0);

        assertReceived(upload(pending, TIPO, SampleFiles.pdf("lido")), TIPO, 1);
        assertReceived(upload(pending, OTHER_TIPO, SampleFiles.photo("a"), SampleFiles.photo("b")), OTHER_TIPO, 2);

        assertThat(itemFor(TIPO.name())).containsEntry("formato_envio", "pdf").containsEntry("documentos_enviados", 1);
        assertThat(itemFor(OTHER_TIPO.name())).containsEntry("formato_envio", "fotos")
                .containsEntry("documentos_enviados", 2);
    }
}
