package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.matheusmelo.kybr.acceptance.upload.fixtures.BrowserMultipart;
import com.matheusmelo.kybr.acceptance.upload.fixtures.SampleFiles;
import com.matheusmelo.kybr.exception.GlobalExceptionHandler;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import com.matheusmelo.kybr.support.web.RawHttp;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * A multipart body the container can't parse is the client's mistake: the upload answers 400
 * {@code REQUISICAO_INVALIDA} with the envelope and the CORS headers, logs no error, and leaves
 * nothing behind.
 */
class MalformedUploadBodyAcceptanceIT extends UploadAcceptanceTest {

    @Test
    void aBodyWhoseClosingBoundaryIsCutOffIsABadRequest() throws Exception {
        BrowserMultipart form = new BrowserMultipart().field("tipo", TIPO.name())
                .file("arquivos", "foto.jpg", "image/jpeg", SampleFiles.photo("cortada"));
        byte[] whole = form.body();
        byte[] cut = Arrays.copyOf(whole, whole.length - 30);

        assertBadRequest(form.head(uploadPath(pending), cut, ALLOWED_ORIGIN), cut);
    }

    @Test
    void aMultipartTypeWithoutABoundaryIsABadRequest() throws Exception {
        BrowserMultipart form = new BrowserMultipart().field("tipo", TIPO.name())
                .file("arquivos", "foto.jpg", "image/jpeg", SampleFiles.photo("sem-limite"));
        byte[] body = form.body();
        String head = new String(form.head(uploadPath(pending), body, ALLOWED_ORIGIN), StandardCharsets.ISO_8859_1)
                .replace(form.contentType(), "multipart/form-data");

        assertBadRequest(head.getBytes(StandardCharsets.ISO_8859_1), body);
    }

    @Test
    void aNestedMixedPartWithABrokenInnerBoundaryIsABadRequest() throws Exception {
        String inner = "--interno\r\nContent-Disposition: file; filename=\"a.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
                + "sem fim";
        byte[] body = ("--externo\r\nContent-Disposition: form-data; name=\"tipo\"\r\n\r\n" + TIPO.name() + "\r\n"
                + "--externo\r\nContent-Disposition: form-data; name=\"arquivos\"\r\n"
                + "Content-Type: multipart/mixed; boundary=interno\r\n\r\n" + inner + "\r\n"
                + "--externo").getBytes(StandardCharsets.ISO_8859_1);
        String head = "POST " + uploadPath(pending) + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: multipart/form-data; boundary=externo\r\nContent-Length: " + body.length
                + "\r\nOrigin: " + ALLOWED_ORIGIN;

        assertBadRequest(head.getBytes(StandardCharsets.ISO_8859_1), body);
    }

    private void assertBadRequest(byte[] head, byte[] body) throws Exception {
        RawHttp.Response response;
        try (CapturedEvents log = CapturedEvents.of(GlobalExceptionHandler.class)) {
            response = RawHttp.send(port, head, body);

            assertThat(log.events()).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
        }

        assertThat(response.status()).as(response.body()).isEqualTo(400);
        assertThat(response.header("Content-Type")).startsWith("application/json");
        assertThat(response.body()).contains("\"code\":\"REQUISICAO_INVALIDA\"");
        assertCors(response);
        assertThat(storage.keys()).isEmpty();
        assertThat(documentsOf(pending.linkCase().id())).isEmpty();
        assertNothingReserved(pending.linkCase().id());
        assertNoTempFileLeft();
    }
}
