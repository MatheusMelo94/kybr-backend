package com.matheusmelo.kybr.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.upload.scan.ScanDispatcher;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.upload.MultipartParseSpy;
import com.matheusmelo.kybr.support.upload.TestFiles;
import com.matheusmelo.kybr.support.upload.TestImages;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import com.matheusmelo.kybr.support.upload.UploadWebIntegrationTest;
import com.matheusmelo.kybr.support.web.LimiterClock;
import com.matheusmelo.kybr.support.web.RawHttp;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * E06 over HTTP: every outcome's status and code, no BFF key, no conditional headers, the token
 * kept out of logs, the multipart body left unparsed until the cheap checks pass, and the multipart
 * temporary directory empty after every exit.
 */
@ExtendWith(OutputCaptureExtension.class)
class UploadControllerIT extends UploadWebIntegrationTest {

    @Autowired
    private ScanDispatcher scans;

    private static final int TEN_MIB = 10 * 1024 * 1024;

    @BeforeEach
    void aFreshRateLimitWindow() {
        // every test in its own window, so the shared loopback address never runs into the per-IP limit
        LimiterClock.INSTANCE.freeze(Instant.now().plus(Duration.ofDays(ThreadLocalRandom.current().nextInt(1, 3_650))));
    }

    @AfterEach
    void releaseTheWindow() {
        LimiterClock.INSTANCE.release();
    }

    private static String path(String slug, String token) {
        return "/public/v/" + slug + "/cases/" + token + "/documents";
    }

    private String path() {
        return path(workspace.slug(), pending.token());
    }

    private RequestSpecification multipart(String tipo, byte[]... files) {
        RequestSpecification request = httpWithoutKey().multiPart("tipo", tipo);
        for (int i = 0; i < files.length; i++) {
            request = request.multiPart("arquivos", "documento-" + i + ".bin", files[i], "application/octet-stream");
        }
        return request;
    }

    private Response upload(String path, String tipo, byte[]... files) {
        return multipart(tipo, files).post(path);
    }

    private long tempFiles() throws IOException {
        Path dir = properties.upload().tempDir();
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private void assertNoTempFileLeft() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(tempFiles()).isZero());
    }

    private static void assertError(Response response, int status, String code) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        assertThat(response.jsonPath().getString("code")).isEqualTo(code);
    }

    private TestCases.Built seedPendingCaseHolding(Map<DocumentoTipo, DocumentosTipo> held) {
        Instant now = Instant.now();
        Resultado resultado = new Resultado("EMPRESA EXEMPLO LTDA", now, null, now.plus(Duration.ofDays(2)), null,
                List.of(new ItemResultado(CodigoResultado.DOCUMENTO_RESPONSAVEL, null, null, null, null, TIPO, 0)),
                null);
        TestCases.Built built = TestCases.builder(workspace).estado(EstadoCaso.pending).resultado(resultado)
                .documentos(held).build();
        mongo.insert(built.linkCase());
        return built;
    }

    // --- outcomes ------------------------------------------------------------------------------

    @Test
    void receivedPhotosAre202WithTheTypeCountAndStateAndNoKeyOrConditionalHeaders(CapturedOutput output)
            throws IOException {
        Response response = upload(path(), TIPO.name(), TestImages.jpegWithMetadata(32, 24), TestImages.png(20, 20));

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(202);
        assertThat(response.jsonPath().getString("tipo")).isEqualTo(TIPO.name());
        assertThat(response.jsonPath().getInt("documentos_enviados")).isEqualTo(2);
        assertThat(response.jsonPath().getString("situacao_envio")).isEqualTo("verificando");
        assertThat(response.jsonPath().getMap("$")).containsOnlyKeys("tipo", "documentos_enviados", "situacao_envio");
        assertThat(response.header("ETag")).isNull();
        assertThat(response.header("Last-Modified")).isNull();
        assertThat(storage.quarantineKeys()).hasSize(2);
        assertThat(storage.keys()).isEmpty();
        assertThat(scanner.calls()).isZero();
        assertNoTempFileLeft();
        assertThat(output.getAll()).doesNotContain(pending.token()).doesNotContain("documento-0.bin");
    }

    @Test
    void aRetryOfTheSamePhotoAnswersTheSameCount() {
        byte[] photo = TestImages.jpeg(16, 16);
        upload(path(), TIPO.name(), photo);

        Response retry = upload(path(), TIPO.name(), photo);

        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(retry.jsonPath().getInt("documentos_enviados")).isEqualTo(1);
        assertThat(storage.quarantineKeys()).hasSize(1);
    }

    @Test
    void aTypeOverTenMebibytesIs413ArquivoGrande() throws IOException {
        TestCases.Built full = seedPendingCaseHolding(Map.of(TIPO, new DocumentosTipo(FormatoEnvio.fotos, 1, 10_485_700)));

        assertError(upload(path(workspace.slug(), full.token()), TIPO.name(), TestImages.jpeg(16, 16)), 413,
                "ARQUIVO_GRANDE");
        assertNoTempFileLeft();
    }

    @Test
    void aCaseWithThirtyFilesIs413LimiteArquivos() throws IOException {
        TestCases.Built full = seedPendingCaseHolding(Map.of(OTHER_TIPO, new DocumentosTipo(FormatoEnvio.fotos, 30, 3_000)));

        assertError(upload(path(workspace.slug(), full.token()), TIPO.name(), TestImages.jpeg(16, 16)), 413,
                "LIMITE_ARQUIVOS");
        assertNoTempFileLeft();
    }

    @Test
    void rawEicarIsRefusedByTheMagicBytesWithoutAScannerCall() throws IOException {
        assertError(upload(path(), TIPO.name(), TestFiles.eicar()), 415, "FORMATO_INVALIDO");

        assertThat(scanner.calls()).isZero();
        assertNoTempFileLeft();
    }

    @Test
    void aMarkedPdfIsReceivedAndThenRefusedByTheScanJob() throws IOException {
        assertThat(upload(path(), TIPO.name(), TestPdfs.withScannerMarker()).statusCode()).isEqualTo(202);

        scans.runOnce();

        assertThat(scanner.calls()).isEqualTo(1);
        assertThat(storage.keys()).isEmpty();
        assertThat(storage.quarantineKeys()).isEmpty();
        assertNoTempFileLeft();
    }

    @Test
    void aJsonBodyIs415BeforeTheBodyIsRead() {
        Response response = httpWithoutKey().contentType("application/json").body("{\"tipo\":\"RESPONSAVEL\"}")
                .post(path());

        assertError(response, 415, "MIDIA_NAO_SUPORTADA");
        assertThat(MultipartParseSpy.parseCalls()).isZero();
    }

    @Test
    void aDecidedCaseIs409() throws IOException {
        TestCases.Built approved = seedCase(EstadoCaso.approved);

        assertError(upload(path(workspace.slug(), approved.token()), TIPO.name(), TestImages.jpeg(8, 8)), 409,
                "CASO_DECIDIDO");
        assertNoTempFileLeft();
    }

    @Test
    void anUnknownLinkAndAnUnknownTokenAre404() throws IOException {
        assertError(upload(path("loja-inexistente", pending.token()), TIPO.name(), TestImages.jpeg(8, 8)), 404,
                "LINK_NAO_ENCONTRADO");
        assertError(upload(path(workspace.slug(), TestCases.newToken()), TIPO.name(), TestImages.jpeg(8, 8)), 404,
                "CASO_NAO_ENCONTRADO");
        assertNoTempFileLeft();
    }

    @Test
    void tooManyRequestsFromOneAddressAre429WithRetryAfter() throws IOException {
        Response last = null;
        for (int i = 0; i <= properties.upload().limits().ip().limit(); i++) {
            last = upload(path("loja-inexistente", pending.token()), TIPO.name(), TestImages.jpeg(8, 8));
        }

        assertError(last, 429, "LIMITE_TENTATIVAS");
        assertThat(last.header("Retry-After")).isNotBlank();
        assertNoTempFileLeft();
    }

    @Test
    void anUnavailableScannerDoesntFailTheUpload() throws IOException {
        scanner.unavailable();

        Response response = upload(path(), TIPO.name(), TestImages.jpeg(8, 8));
        scans.runOnce();

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(storage.quarantineKeys()).hasSize(1);
        assertNoTempFileLeft();
    }

    @Test
    void anUnavailableStorageIs503WithRetryAfter() throws IOException {
        storage.failPutOnCall(1);

        Response response = upload(path(), TIPO.name(), TestImages.jpeg(8, 8));

        assertError(response, 503, "INDISPONIVEL");
        assertThat(response.header("Retry-After")).isEqualTo("30");
        assertNoTempFileLeft();
    }

    @Test
    void anUnexpectedFailureIs500AndLeavesNoTempFile() throws IOException {
        storage.throwingOnPut(new IllegalStateException("synthetic failure"));

        assertError(upload(path(), TIPO.name(), TestImages.jpeg(8, 8)), 500, "ERRO_INTERNO");
        assertThat(storage.quarantineKeys()).isEmpty();
        assertNoTempFileLeft();
    }

    @Test
    void aClientThatAbortsMidUploadLeavesNoTempFile() throws IOException {
        byte[] body = multipartBody(TIPO.name(), new byte[TEN_MIB / 2]);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(head(path(), body.length));
            out.write("\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.write(body, 0, body.length / 2); // half the body, then gone
            out.flush();
        }

        assertNoTempFileLeft();
        assertThat(storage.keys()).isEmpty();
    }

    // --- the body stays unparsed until the cheap checks pass -------------------------------------

    private RawHttp.Response tenMebibytes(String path) throws IOException {
        byte[] body = multipartBody(TIPO.name(), new byte[TEN_MIB - 4096]);
        RawHttp.Outcome outcome = RawHttp.sendUntilClosed(port, head(path, body.length), body);
        assertThat(outcome.response()).as("a response before the body was read").isNotNull();
        return outcome.response();
    }

    private void assertAnsweredUnparsed(RawHttp.Response response, int status, String code) throws IOException {
        assertThat(response.status()).as(response.toString()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
        assertThat(MultipartParseSpy.parseCalls()).as("nothing asked for the parts").isZero();
        assertThat(tempFiles()).isZero();
    }

    @Test
    void anUnknownLinkIsAnsweredWithoutParsingTheBody() throws IOException {
        assertAnsweredUnparsed(tenMebibytes(path("loja-inexistente", pending.token())), 404, "LINK_NAO_ENCONTRADO");
    }

    @Test
    void aDisabledLinkIsAnsweredWithoutParsingTheBody() throws IOException {
        Workspace disabled = TestWorkspaces.builder().linkAtivo(false).build();
        mongo.insert(disabled);

        assertAnsweredUnparsed(tenMebibytes(path(disabled.slug(), pending.token())), 404, "LINK_NAO_ENCONTRADO");
    }

    @Test
    void anUnknownTokenIsAnsweredWithoutParsingTheBody() throws IOException {
        assertAnsweredUnparsed(tenMebibytes(path(workspace.slug(), TestCases.newToken())), 404, "CASO_NAO_ENCONTRADO");
    }

    @Test
    void aDecidedCaseIsAnsweredWithoutParsingTheBody() throws IOException {
        TestCases.Built blocked = seedCase(EstadoCaso.blocked);

        assertAnsweredUnparsed(tenMebibytes(path(workspace.slug(), blocked.token())), 409, "CASO_DECIDIDO");
    }

    @Test
    void aThrottledAddressIsAnsweredWithoutParsingTheBody() throws IOException {
        for (int i = 0; i < properties.upload().limits().ip().limit(); i++) {
            upload(path("loja-inexistente", pending.token()), TIPO.name(), TestImages.jpeg(8, 8));
        }
        MultipartParseSpy.reset();

        assertAnsweredUnparsed(tenMebibytes(path()), 429, "LIMITE_TENTATIVAS");
    }

    @Test
    void anAcceptedUploadIsParsedAfterItsChecks() {
        Response response = upload(path(), TIPO.name(), TestImages.jpeg(8, 8));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(MultipartParseSpy.parseCalls()).isPositive();
    }

    // --- raw multipart -------------------------------------------------------------------------

    private static final String BOUNDARY = "kybr-test-boundary-7f3a";

    private static byte[] head(String path, int length) {
        return ("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary="
                + BOUNDARY + "\r\nContent-Length: " + length).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] multipartBody(String tipo, byte[] file) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(file.length + 512);
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"tipo\"\r\n\r\n" + tipo + "\r\n--"
                + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"arquivos\"; filename=\"foto.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        byte[] photo = file.clone();
        photo[0] = (byte) 0xFF;
        photo[1] = (byte) 0xD8;
        photo[2] = (byte) 0xFF;
        out.writeBytes(photo);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }
}
