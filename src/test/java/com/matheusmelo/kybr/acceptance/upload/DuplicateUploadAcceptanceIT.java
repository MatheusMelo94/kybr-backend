package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.matheusmelo.kybr.acceptance.upload.fixtures.BrowserMultipart;
import com.matheusmelo.kybr.acceptance.upload.fixtures.SampleFiles;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.support.upload.ScriptedScanner;
import io.restassured.response.Response;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The seller's page resends the very same files whenever it can't read an answer, so a file already
 * stored for the case and type is ignored: the answer reports the current count, and no page, byte
 * or file is added. That holds for photos too, whose stored bytes differ from what was sent.
 */
class DuplicateUploadAcceptanceIT extends UploadAcceptanceTest {

    private void assertHolds(int files, long bytes) {
        LinkCase stored = stored(pending.linkCase().id());
        assertThat(stored.documentos().get(TIPO).arquivos()).isEqualTo(files);
        assertThat(stored.documentos().get(TIPO).bytes()).isEqualTo(bytes);
        assertThat(documentsOf(pending.linkCase().id())).hasSize(files);
        assertThat(storage.keys()).hasSize(files);
    }

    @Test
    void theSamePdfTwiceKeepsOnePage() {
        byte[] pdf = SampleFiles.pdf("repetido");
        assertReceived(upload(pending, TIPO, pdf), TIPO, 1);

        assertReceived(upload(pending, TIPO, pdf), TIPO, 1, "aceito");

        assertHolds(1, pdf.length);
    }

    @Test
    void theSameTwoPhotosTwiceKeepTwoPages() {
        byte[] a = SampleFiles.photo("a");
        byte[] b = SampleFiles.photo("b");
        assertReceived(upload(pending, TIPO, a, b), TIPO, 2);

        assertReceived(upload(pending, TIPO, a, b), TIPO, 2, "aceito");

        assertHolds(2, a.length + b.length);
    }

    @Test
    void theSamePhotoTwiceInOneRequestCountsOnce() {
        byte[] photo = SampleFiles.photo("dobrada");

        assertReceived(upload(pending, TIPO, photo, photo), TIPO, 1);

        scanAll();
        assertHolds(1, photo.length);
    }

    @Test
    void twoKnownPhotosAndOneNewAddOnePage() {
        byte[] a = SampleFiles.photo("a");
        byte[] b = SampleFiles.photo("b");
        byte[] c = SampleFiles.photo("c");
        assertReceived(upload(pending, TIPO, a, b), TIPO, 2);

        assertReceived(upload(pending, TIPO, a, b, c), TIPO, 3);

        assertHolds(3, a.length + b.length + c.length);
    }

    @Test
    void twoIdenticalUploadsAtTheSameTimeAreBothReceivedAndCountOnce() throws Exception {
        byte[] photo = SampleFiles.photo("simultanea");
        ScriptedScanner.Gate gate = storage.holdPuts();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> first = pool.submit(() -> multipart(TIPO.name(), photo).post(uploadPath(pending)));
            Future<Response> second = pool.submit(() -> multipart(TIPO.name(), photo).post(uploadPath(pending)));
            await().atMost(Duration.ofSeconds(10)).until(() -> gate.waiting() == 2);
            gate.release();

            // the one that met the other at the commit gives its copy back and answers the same count
            assertReceived(first.get(20, TimeUnit.SECONDS), TIPO, 1);
            assertReceived(second.get(20, TimeUnit.SECONDS), TIPO, 1);
        } finally {
            gate.release();
            pool.shutdownNow();
        }
        scanAll();
        assertHolds(1, photo.length);
    }

    @Test
    void aResendAfterALostAnswerAddsNothing() throws Exception {
        byte[] a = SampleFiles.photo("perdida-a");
        byte[] b = SampleFiles.photo("perdida-b");
        BrowserMultipart form = new BrowserMultipart().field("tipo", TIPO.name())
                .file("arquivos", "a.jpg", "image/jpeg", a).file("arquivos", "b.jpg", "image/jpeg", b);
        byte[] body = form.body();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(form.head(uploadPath(pending), body, ALLOWED_ORIGIN));
            out.write("\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } // gone before reading the answer
        await().atMost(Duration.ofSeconds(10)).until(() -> documentsOf(pending.linkCase().id()).size() == 2);

        assertReceived(upload(pending, TIPO, a, b), TIPO, 2);

        assertHolds(2, a.length + b.length);
    }

    @Test
    void theSameFileUnderAnotherTypeIsANewPage() {
        byte[] photo = SampleFiles.photo("dois-tipos");
        assertReceived(upload(pending, TIPO, photo), TIPO, 1);

        assertReceived(upload(pending, OTHER_TIPO, photo), OTHER_TIPO, 1);
    }
}
