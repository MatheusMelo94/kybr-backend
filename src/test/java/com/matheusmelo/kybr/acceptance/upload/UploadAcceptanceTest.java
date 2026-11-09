package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.acceptance.ResponseInvariants;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.service.jobs.JobName;
import com.matheusmelo.kybr.support.jobs.JobTriggers;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.upload.UploadWebIntegrationTest;
import com.matheusmelo.kybr.support.web.LimiterClock;
import com.matheusmelo.kybr.support.web.RawHttp;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Base for the document upload acceptance suite: the whole app on a random port with the scripted
 * scanner, in-memory storage and photo stand-in, driven over HTTP the way the seller's browser
 * calls it (no BFF key, multipart, optionally an {@code Origin}). Shares the upload integration
 * tests' cached context. Every response goes through {@link ResponseInvariants}, checked after each
 * test, and each test runs in its own rate-limit window.
 */
@Tag("b2-upload")
public abstract class UploadAcceptanceTest extends UploadWebIntegrationTest {

    public static final String ALLOWED_ORIGIN = "https://kybr.com.br";
    public static final String CLIENT_IP_HEADER = "X-Kybr-Client-IP";
    /** 10 MiB: the per-type limit, counted on the original bytes. */
    protected static final int TEN_MIB = 10_485_760;
    /** The upload body cap: 10 MiB of files plus 256 KiB of multipart overhead. */
    protected static final int BODY_CAP = 10_747_904;

    protected final ResponseInvariants invariants = new ResponseInvariants();

    @Autowired
    protected JobTriggers jobs;

    @Override
    protected RequestSpecification httpWithoutKey() {
        return super.httpWithoutKey().filter(invariants);
    }

    @BeforeEach
    void aFreshRateLimitWindow() {
        LimiterClock.INSTANCE.freeze(Instant.now().plus(Duration.ofDays(ThreadLocalRandom.current().nextInt(1, 3_650))));
    }

    @AfterEach
    void everyResponseKeepsTheCrossCuttingInvariants() {
        LimiterClock.INSTANCE.release();
        invariants.verifyAndReset();
    }

    // --- data ------------------------------------------------------------------------------------

    /** A pending case of {@link #workspace} asking for {@link #TIPO}, already holding {@code held}. */
    protected TestCases.Built seedCaseHolding(Map<DocumentoTipo, DocumentosTipo> held) {
        TestCases.Built built = TestCases.builder(workspace).estado(EstadoCaso.pending)
                .resultado(resultado(List.of(item(CodigoResultado.DOCUMENTO_RESPONSAVEL, TIPO)))).documentos(held)
                .build();
        mongo.insert(built.linkCase());
        return built;
    }

    /** A case of {@link #workspace} in {@code estado} whose result lists exactly {@code items}. */
    protected TestCases.Built seedCaseWith(EstadoCaso estado, List<ItemResultado> items) {
        TestCases.Built built = TestCases.builder(workspace).estado(estado).resultado(resultado(items)).build();
        mongo.insert(built.linkCase());
        return built;
    }

    protected static ItemResultado item(CodigoResultado codigo, DocumentoTipo tipo) {
        return new ItemResultado(codigo, null, null, null, null, tipo, tipo == null ? null : 0);
    }

    protected static Resultado resultado(List<ItemResultado> items) {
        Instant now = Instant.now();
        return new Resultado("EMPRESA EXEMPLO LTDA", now, null, now.plus(Duration.ofDays(2)), null, items, null);
    }

    protected LinkCase stored(UUID caseId) {
        return mongo.findById(caseId, LinkCase.class);
    }

    protected List<Document> documentsOf(UUID caseId) {
        return mongo.find(query(where(Document.CASE_ID).is(caseId)), Document.class);
    }

    protected List<AuditEvent> auditsOf(UUID caseId) {
        return mongo.find(query(where("case_id").is(caseId)), AuditEvent.class);
    }

    /** The case holds no reserved bytes or files for any type (a refusal or a failure released them). */
    protected void assertNothingReserved(UUID caseId) {
        LinkCase linkCase = stored(caseId);
        if (linkCase.documentos() != null) {
            assertThat(linkCase.documentos().values()).allSatisfy(held -> {
                assertThat(held.arquivos()).as("reserved files").isZero();
                assertThat(held.bytes()).as("reserved bytes").isZero();
            });
        }
        assertThat(linkCase.arquivosTotal() == null ? 0 : linkCase.arquivosTotal()).as("files on the case").isZero();
    }

    // --- requests --------------------------------------------------------------------------------

    protected static String uploadPath(String slug, String token) {
        return "/public/v/" + slug + "/cases/" + token + "/documents";
    }

    protected String uploadPath(TestCases.Built linkCase) {
        return uploadPath(workspace.slug(), linkCase.token());
    }

    /** A browser-style multipart request: the {@code tipo} part, then one {@code arquivos} part per file. */
    protected RequestSpecification multipart(String tipo, byte[]... files) {
        RequestSpecification request = httpWithoutKey();
        if (tipo != null) {
            request = request.multiPart("tipo", tipo);
        }
        for (int i = 0; i < files.length; i++) {
            request = request.multiPart("arquivos", "pagina-" + (i + 1) + ".bin", files[i], "application/octet-stream");
        }
        return request;
    }

    /**
     * Posts the upload and, when it was received (202), runs the scan job, so what follows sees the
     * files verified, as a seller's later case read would. The answer is the upload's own (sent
     * before any scan).
     */
    protected Response upload(String path, String tipo, byte[]... files) {
        return scanned(multipart(tipo, files).post(path));
    }

    protected Response upload(TestCases.Built linkCase, DocumentoTipo tipo, byte[]... files) {
        return upload(uploadPath(linkCase), tipo.name(), files);
    }

    protected Response uploadFrom(String origin, String path, String tipo, byte[]... files) {
        return scanned(multipart(tipo, files).header("Origin", origin).post(path));
    }

    /** Runs the scan job after a received (202) upload; returns the upload's answer. */
    protected Response scanned(Response response) {
        if (response.statusCode() == 202) {
            scanAll();
        }
        return response;
    }

    /** The seller's case read through the BFF. */
    protected Response readCase(String slug, String token) {
        return http().header(CLIENT_IP_HEADER, randomIp()).get("/public/v/" + slug + "/cases/" + token);
    }

    /** The seller's contact change through the BFF. */
    protected Response putEmail(String slug, String token, String email) {
        return http().header(CLIENT_IP_HEADER, randomIp()).contentType(ContentType.JSON).body(Map.of("email", email))
                .put("/public/v/" + slug + "/cases/" + token + "/contact");
    }

    // --- temp directory --------------------------------------------------------------------------

    protected long tempFiles() throws IOException {
        Path dir = properties.upload().tempDir();
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    protected void assertNoTempFileLeft() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(tempFiles()).isZero());
    }

    // --- assertions ------------------------------------------------------------------------------

    /** Status, JSON content type and exactly {@code {code, message[, field]}} with the code's own message. */
    protected static Map<String, Object> assertError(Response response, int status, String code) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        assertThat(response.contentType()).startsWith("application/json");
        Map<String, Object> body = response.jsonPath().getMap("");
        assertThat(body.keySet()).as(response.asString()).contains("code", "message").isSubsetOf("code", "message", "field");
        assertThat(body.get("code")).isEqualTo(code);
        assertThat(body.get("message")).isEqualTo(ErrorCode.valueOf(code).message());
        return body;
    }

    /** 202 with the type, its counting files and {@code situacao_envio=verificando}. */
    protected static void assertReceived(Response response, DocumentoTipo tipo, int documentosEnviados) {
        assertReceived(response, tipo, documentosEnviados, "verificando");
    }

    protected static void assertReceived(Response response, DocumentoTipo tipo, int documentosEnviados,
            String situacaoEnvio) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(202);
        assertThat(response.jsonPath().getMap("")).containsOnlyKeys("tipo", "documentos_enviados", "situacao_envio")
                .containsEntry("tipo", tipo.name()).containsEntry("documentos_enviados", documentosEnviados)
                .containsEntry("situacao_envio", situacaoEnvio);
    }

    /** Runs the scan job now (the test profile doesn't schedule it). */
    protected void scanAll() {
        jobs.run(JobName.SCAN);
    }

    /** The headers an allowed origin needs to read the answer, and never credentials. */
    protected static void assertCors(Response response) {
        assertThat(response.header("Access-Control-Allow-Origin")).as("allow origin on " + response.statusCode())
                .isEqualTo(ALLOWED_ORIGIN);
        assertThat(response.header("Access-Control-Expose-Headers")).as("exposed headers on " + response.statusCode())
                .isEqualTo("Retry-After, X-Request-Id");
        assertThat(String.join(",", response.headers().getValues("Vary"))).as("Vary on " + response.statusCode())
                .contains("Origin");
        assertThat(response.header("Access-Control-Allow-Credentials")).isNull();
    }

    protected static void assertCors(RawHttp.Response response) {
        assertThat(response.header("Access-Control-Allow-Origin")).as("allow origin on " + response.status())
                .isEqualTo(ALLOWED_ORIGIN);
        assertThat(response.header("Access-Control-Expose-Headers")).isEqualTo("Retry-After, X-Request-Id");
        assertThat(response.header("Vary")).contains("Origin");
        assertThat(response.header("Access-Control-Allow-Credentials")).isNull();
    }

    /** No {@code Access-Control-*} header at all. */
    protected static void assertNoCorsGrant(Response response) {
        assertThat(response.headers().asList()).as("no CORS grant on " + response.statusCode())
                .noneMatch(h -> h.getName().toLowerCase().startsWith("access-control-"));
    }
}
