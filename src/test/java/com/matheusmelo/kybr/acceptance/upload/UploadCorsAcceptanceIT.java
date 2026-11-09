package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.matheusmelo.kybr.acceptance.upload.fixtures.SampleFiles;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.upload.ScriptedScanner;
import com.matheusmelo.kybr.support.web.RawHttp;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The upload as the seller's browser sees it. The page sends a simple cross-origin request (no
 * preflight), so the browser only lets it read the status when the actual response carries
 * {@code Access-Control-Allow-Origin}; without it the page reports a network error and resends the
 * same files. So every status the upload can answer carries the headers for an allowed origin; a
 * disallowed or missing origin is still processed, without any grant; and no other route grants
 * cross-origin access.
 */
class UploadCorsAcceptanceIT extends UploadAcceptanceTest {

    private static final String PREVIEW_ORIGIN = "https://kybr-web-git-nova-tela-kybr.vercel.app";

    private Response fromAllowedOrigin(TestCases.Built linkCase, byte[]... files) {
        return uploadFrom(ALLOWED_ORIGIN, uploadPath(linkCase), TIPO.name(), files);
    }

    // --- every status, read by an allowed origin ---------------------------------------------------

    @Test
    void anAcceptedUploadCarriesTheGrant() {
        Response response = fromAllowedOrigin(pending, SampleFiles.photo("aceita"));

        assertReceived(response, TIPO, 1);
        assertCors(response);
    }

    @Test
    void anUnknownLinkCarriesTheGrant() {
        Response response = uploadFrom(ALLOWED_ORIGIN, uploadPath("loja-inexistente", pending.token()), TIPO.name(),
                SampleFiles.photo("x"));

        assertError(response, 404, "LINK_NAO_ENCONTRADO");
        assertCors(response);
    }

    @Test
    void aDisabledLinkCarriesTheGrant() {
        Workspace disabled = TestWorkspaces.builder().linkAtivo(false).build();
        mongo.insert(disabled);

        Response response = uploadFrom(ALLOWED_ORIGIN, uploadPath(disabled.slug(), pending.token()), TIPO.name(),
                SampleFiles.photo("x"));

        assertError(response, 404, "LINK_NAO_ENCONTRADO");
        assertCors(response);
    }

    @Test
    void anUnknownTokenCarriesTheGrant() {
        Response response = uploadFrom(ALLOWED_ORIGIN, uploadPath(workspace.slug(), TestCases.newToken()), TIPO.name(),
                SampleFiles.photo("x"));

        assertError(response, 404, "CASO_NAO_ENCONTRADO");
        assertCors(response);
    }

    @Test
    void aDecidedCaseCarriesTheGrant() {
        Response response = fromAllowedOrigin(seedCase(EstadoCaso.approved), SampleFiles.photo("x"));

        assertError(response, 409, "CASO_DECIDIDO");
        assertCors(response);
    }

    @Test
    void aTypeOverItsBytesCarriesTheGrant() {
        TestCases.Built full = seedCaseHolding(Map.of(TIPO, new DocumentosTipo(FormatoEnvio.fotos, 1, TEN_MIB - 10)));

        Response response = fromAllowedOrigin(full, SampleFiles.photo("x"));

        assertError(response, 413, "ARQUIVO_GRANDE");
        assertCors(response);
    }

    @Test
    void aCaseAtThirtyFilesCarriesTheGrant() {
        TestCases.Built full = seedCaseHolding(Map.of(OTHER_TIPO, new DocumentosTipo(FormatoEnvio.fotos, 30, 3_000)));

        Response response = fromAllowedOrigin(full, SampleFiles.photo("x"));

        assertError(response, 413, "LIMITE_ARQUIVOS");
        assertCors(response);
    }

    @Test
    void aBodyOverTheCapRefusedBeforeParsingCarriesTheGrant() throws Exception {
        byte[] body = new byte[BODY_CAP + 1];
        String head = "POST " + uploadPath(pending) + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: multipart/form-data; boundary=----WebKitFormBoundaryAAAAAAAAAAAAAAAA\r\n"
                + "Content-Length: " + body.length + "\r\nOrigin: " + ALLOWED_ORIGIN;

        RawHttp.Outcome outcome = RawHttp.sendUntilClosed(port, head.getBytes(StandardCharsets.ISO_8859_1), body);

        assertThat(outcome.response()).as("an answer before the body was read").isNotNull();
        assertThat(outcome.response().status()).isEqualTo(413);
        assertThat(outcome.response().body()).contains("\"code\":\"REQUISICAO_GRANDE\"");
        assertCors(outcome.response());
        assertThat(outcome.response().header("X-Request-Id")).isNotBlank();
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void aFileThatIsNoPdfOrPhotoCarriesTheGrant() {
        Response response = fromAllowedOrigin(pending, SampleFiles.wrongMagic().get("gif"));

        assertError(response, 415, "FORMATO_INVALIDO");
        assertCors(response);
    }

    @Test
    void aBodyThatIsNotMultipartCarriesTheGrant() {
        Response response = httpWithoutKey().header("Origin", ALLOWED_ORIGIN).contentType("application/json")
                .body("{\"tipo\":\"RESPONSAVEL\"}").post(uploadPath(pending));

        assertError(response, 415, "MIDIA_NAO_SUPORTADA");
        assertCors(response);
    }

    @Test
    void aThrottledAddressCarriesTheGrant() {
        for (int i = 0; i < properties.upload().limits().ip().limit(); i++) {
            upload(uploadPath("loja-inexistente", pending.token()), TIPO.name(), SampleFiles.photo("x"));
        }

        Response response = fromAllowedOrigin(pending, SampleFiles.photo("x"));

        assertError(response, 429, "LIMITE_TENTATIVAS");
        assertThat(response.header("Retry-After")).matches("^[1-9]\\d*$");
        assertCors(response);
    }

    @Test
    void twoIdenticalUploadsAtTheSameMomentBothCarryTheGrantOnTheir202() throws Exception {
        byte[] photo = SampleFiles.photo("simultanea");
        ScriptedScanner.Gate gate = storage.holdPuts();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // posted without running the scan job, so both answers describe the file still waiting
            Future<Response> a = pool.submit(() -> multipart(TIPO.name(), photo).header("Origin", ALLOWED_ORIGIN)
                    .post(uploadPath(pending)));
            Future<Response> b = pool.submit(() -> multipart(TIPO.name(), photo).header("Origin", ALLOWED_ORIGIN)
                    .post(uploadPath(pending)));
            await().atMost(Duration.ofSeconds(10)).until(() -> gate.waiting() == 2);
            gate.release();

            for (Response response : List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))) {
                assertReceived(response, TIPO, 1);
                assertCors(response);
            }
        } finally {
            gate.release();
            pool.shutdownNow();
        }
    }

    @Test
    void anUnavailableScannerDoesntFailTheUploadWhichCarriesTheGrant() {
        scanner.unavailable();

        Response response = fromAllowedOrigin(pending, SampleFiles.photo("x"));

        assertReceived(response, TIPO, 1);
        assertCors(response);
    }

    @Test
    void aMachineAtItsUploadCapacityCarriesTheGrantAndStoresNothing() throws Exception {
        TestCases.Built second = seedCase(EstadoCaso.pending);
        TestCases.Built third = seedCase(EstadoCaso.pending);
        ScriptedScanner.Gate gate = storage.holdPuts();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> a = pool.submit(() -> upload(pending, TIPO, SampleFiles.photo("primeira")));
            Future<Response> b = pool.submit(() -> upload(second, TIPO, SampleFiles.photo("segunda")));
            await().atMost(Duration.ofSeconds(10)).until(() -> gate.waiting() == 2);

            Response refused = fromAllowedOrigin(third, SampleFiles.photo("terceira"));

            assertError(refused, 503, "INDISPONIVEL");
            assertThat(refused.header("Retry-After")).isEqualTo("5");
            assertCors(refused);
            assertThat(documentsOf(third.linkCase().id())).isEmpty();
            gate.release();
            assertReceived(a.get(20, TimeUnit.SECONDS), TIPO, 1);
            assertReceived(b.get(20, TimeUnit.SECONDS), TIPO, 1);
        } finally {
            gate.release();
            pool.shutdownNow();
        }
        assertReceived(fromAllowedOrigin(third, SampleFiles.photo("terceira")), TIPO, 1);
    }

    @Test
    void anUnexpectedFailureCarriesTheGrant() {
        storage.throwingOnPut(new IllegalStateException("falha sintetica"));

        Response response = fromAllowedOrigin(pending, SampleFiles.photo("x"));

        assertError(response, 500, "ERRO_INTERNO");
        assertCors(response);
    }

    @Test
    void aStorageFailureCarriesTheGrant() {
        storage.failPutOnCall(1);

        Response response = fromAllowedOrigin(pending, SampleFiles.photo("x"));

        assertError(response, 503, "INDISPONIVEL");
        assertCors(response);
    }

    // --- origins -------------------------------------------------------------------------------

    @Test
    void theConfiguredPreviewOriginIsGranted() {
        Response response = uploadFrom(PREVIEW_ORIGIN, uploadPath(pending), TIPO.name(), SampleFiles.photo("x"));

        assertReceived(response, TIPO, 1);
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo(PREVIEW_ORIGIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.test", "http://kybr.com.br", "https://kybr.com.br.evil.test",
        "https://evilkybr.com.br", "https://kybr.com.br:444", "https://kybr.com.br/", "null",
        "https://kybr-web-git-nova-tela-kybr.vercel.app.evil.test", "https://outro-projeto.vercel.app"})
    void aDisallowedOriginIsProcessedWithoutAGrant(String origin) {
        Response response = uploadFrom(origin, uploadPath(pending), TIPO.name(), SampleFiles.photo("x"));

        assertReceived(response, TIPO, 1);
        assertNoCorsGrant(response);
        assertThat(response.asString()).doesNotContain("Invalid CORS request");
        assertThat(documentsOf(pending.linkCase().id())).hasSize(1);
    }

    @Test
    void aRequestWithoutAnOriginIsProcessedWithoutAGrant() {
        Response response = upload(pending, TIPO, SampleFiles.photo("x"));

        assertReceived(response, TIPO, 1);
        assertNoCorsGrant(response);
    }

    @Test
    void aDisallowedOriginGetsTheSameRefusalAsNoOrigin() {
        TestCases.Built decided = seedCase(EstadoCaso.blocked);

        Response withEvil = uploadFrom("https://evil.test", uploadPath(decided), TIPO.name(), SampleFiles.photo("x"));
        Response without = upload(decided, TIPO, SampleFiles.photo("x"));

        assertError(withEvil, 409, "CASO_DECIDIDO");
        assertThat(withEvil.asString()).isEqualTo(without.asString());
        assertNoCorsGrant(withEvil);
    }

    // --- preflight -----------------------------------------------------------------------------

    private Response preflight(String origin, String method, String headers) {
        var request = httpWithoutKey().header("Origin", origin).header("Access-Control-Request-Method", method);
        if (headers != null) {
            request = request.header("Access-Control-Request-Headers", headers);
        }
        return request.options(uploadPath(pending));
    }

    @Test
    void anAllowedPreflightIsGrantedPostWithTheContentTypeOnlyAndNoCredentials() {
        Response response = preflight(ALLOWED_ORIGIN, "POST", "content-type");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo(ALLOWED_ORIGIN);
        assertThat(response.header("Access-Control-Allow-Methods")).isEqualTo("POST, OPTIONS");
        assertThat(response.header("Access-Control-Allow-Headers")).isEqualTo("Content-Type");
        assertThat(response.header("Access-Control-Max-Age")).isEqualTo("600");
        assertThat(response.header("Access-Control-Allow-Credentials")).isNull();
        assertThat(String.join(",", response.headers().getValues("Vary"))).contains("Origin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.test", "http://kybr.com.br", "https://kybr.com.br.evil.test", "null"})
    void aDisallowedPreflightIsAnsweredWithoutAGrant(String origin) {
        Response response = preflight(origin, "POST", null);

        assertThat(response.statusCode()).isEqualTo(204);
        assertNoCorsGrant(response);
        assertThat(response.asString()).doesNotContain("Invalid CORS request");
    }

    @Test
    void aPreflightAskingForTheKeyAuthorizationOrAnotherMethodIsNotGranted() {
        for (Response response : List.of(preflight(ALLOWED_ORIGIN, "POST", "x-kybr-bff-key"),
                preflight(ALLOWED_ORIGIN, "POST", "authorization"), preflight(ALLOWED_ORIGIN, "PUT", null))) {
            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(response.header("Access-Control-Allow-Methods")).isNull();
            assertThat(response.header("Access-Control-Allow-Headers")).isNull();
            assertThat(response.header("Access-Control-Allow-Origin")).isNull();
        }
    }

    // --- no other route ------------------------------------------------------------------------

    @Test
    void noOtherRouteGrantsCrossOriginAccess() {
        String slug = workspace.slug();
        String casePath = "/public/v/" + slug + "/cases/" + pending.token();
        List<Response> responses = List.of(
                http().header("Origin", ALLOWED_ORIGIN).get("/public/v/" + slug),
                http().header("Origin", ALLOWED_ORIGIN).header(CLIENT_IP_HEADER, randomIp()).get(casePath),
                httpWithoutKey().header("Origin", ALLOWED_ORIGIN).header("Access-Control-Request-Method", "GET")
                        .options("/public/v/" + slug),
                httpWithoutKey().header("Origin", ALLOWED_ORIGIN).header("Access-Control-Request-Method", "PUT")
                        .options(casePath + "/contact"),
                httpWithoutKey().header("Origin", ALLOWED_ORIGIN).get("/livez"),
                httpWithoutKey().header("Origin", ALLOWED_ORIGIN).post(uploadPath(pending) + "/"));
        for (Response response : responses) {
            assertNoCorsGrant(response);
            assertThat(response.asString()).doesNotContain("Invalid CORS request");
        }
    }
}
