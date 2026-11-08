package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.matheusmelo.kybr.support.upload.MultipartProbeController;
import com.matheusmelo.kybr.support.web.RawHttp;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The document upload's multipart envelope: the body cap before any parsing, the container's part
 * count and part header limits, and the temp directory left empty on every exit.
 */
class MultipartLimitsIT extends WebIntegrationTest {

    private static final long BODY_CAP = 10_747_904;
    private static final int MIB = 1024 * 1024;
    private static final String BOUNDARY = "kybr-test-boundary";

    @Test
    void aBodyOneByteOverTheCapIs413BeforeAnyPartIsParsed() throws IOException {
        String head = "POST /public/v/s/cases/t/documents HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\nContent-Length: " + (BODY_CAP + 1);

        RawHttp.Response response = RawHttp.send(port, head.getBytes(StandardCharsets.ISO_8859_1), new byte[0]);

        assertThat(response.status()).as(response.toString()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":\"REQUISICAO_GRANDE\"");
        assertThat(tempFiles()).isZero();
    }

    @Test
    void thirtyThreePartsAreRefusedByTheContainerWithTheEnvelopeAndNoTempFileLeft() {
        Response response = probe(body(33, 1024, "foto.jpg"));

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(413);
        assertThat(response.jsonPath().getString("code")).isEqualTo("REQUISICAO_GRANDE");
        awaitNoTempFiles();
    }

    @Test
    void thirtyPhotosWithLongUtf8NamesInBothFilenameFormsAreParsed() {
        // 255 three-byte characters, sent raw and percent-encoded: about 3 KiB of headers per part.
        String name = "\u20ac".repeat(251) + ".jpg";
        int fileBytes = (10 * MIB - 4096) / 30;

        Response response = probe(body(30, fileBytes, name));

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("files")).isEqualTo(30);
        assertThat(response.jsonPath().getString("tipo")).isEqualTo("RESPONSAVEL");
        awaitNoTempFiles();
    }

    @Test
    void aPartHeaderOverEightKibibytesIsRefusedWithTheEnvelope() {
        Response response = probe(body(1, 1024, "a".repeat(9 * 1024)));

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(413);
        assertThat(response.jsonPath().getString("code")).isEqualTo("REQUISICAO_GRANDE");
        awaitNoTempFiles();
    }

    private Response probe(byte[] body) {
        assertThat((long) body.length).isLessThanOrEqualTo(BODY_CAP);
        return http().contentType("multipart/form-data; boundary=" + BOUNDARY).body(body)
                .post(MultipartProbeController.PATH);
    }

    /** A {@code tipo} field plus {@code files} photo parts named {@code name} (raw and RFC 5987 forms). */
    private static byte[] body(int files, int fileBytes, String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Random random = new Random(files);
        write(out, "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"tipo\"\r\n\r\nRESPONSAVEL\r\n");
        for (int i = 0; i < files; i++) {
            write(out, "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"arquivos\"; filename=\"" + name
                    + "\"; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
                    + "\r\nContent-Type: image/jpeg\r\n\r\n");
            byte[] photo = new byte[fileBytes];
            random.nextBytes(photo);
            photo[0] = (byte) 0xFF;
            photo[1] = (byte) 0xD8;
            photo[2] = (byte) 0xFF;
            out.writeBytes(photo);
            write(out, "\r\n");
        }
        write(out, "--" + BOUNDARY + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private void awaitNoTempFiles() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(tempFiles()).isZero());
    }

    private long tempFiles() throws IOException {
        Path dir = properties.upload().tempDir();
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
