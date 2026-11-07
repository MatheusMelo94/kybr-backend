package com.matheusmelo.kybr.acceptance.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.acceptance.ResponseInvariants;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.RateLimitCounter;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.CreditLedger;
import com.matheusmelo.kybr.model.link.EstadoProcessamento;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.Seller;
import com.matheusmelo.kybr.model.link.Verification;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.LimiterClock;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Base for the seller-link acceptance suite: the whole app on a random port, driven over HTTP
 * through the BFF, with the provider stub staging runs and the real background verification.
 *
 * <p>Every response goes through two recorders checked after each test: the cross-cutting
 * invariants ({@link ResponseInvariants}: request id, security headers, no redirects or cookies,
 * digits-only {@code Retry-After}) and the frontend's parser ({@link FrontendContract}) on every 2xx.
 *
 * <p>Provider budgets are day and month counters in the shared test database; each test starts
 * with them cleared, so repeated runs on one day never exhaust them.
 */
@Tag("b1-link")
public abstract class LinkAcceptanceTest extends WebIntegrationTest {

    public static final String CLIENT_IP_HEADER = "X-Kybr-Client-IP";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    protected static final Duration OUTCOME_TIMEOUT = Duration.ofSeconds(25);

    /** Processing states after which the worker never touches the case again. */
    protected static final Set<EstadoProcessamento> SETTLED =
            Set.of(EstadoProcessamento.CONCLUIDO, EstadoProcessamento.REVISAO_MANUAL);

    protected final ResponseInvariants invariants = new ResponseInvariants();
    protected final FrontendContract frontend = new FrontendContract();

    @LocalManagementPort
    protected int managementPort;
    @Autowired
    protected WorkspaceRepository workspaces;
    @Autowired
    protected LinkCaseRepository cases;
    @Autowired
    protected MongoTemplate mongo;

    @Override
    protected RequestSpecification httpWithoutKey() {
        return super.httpWithoutKey().filter(invariants).filter(frontend);
    }

    @BeforeEach
    void clearProviderBudgets() {
        mongo.remove(query(where("_id").regex("^budget\\.")), RateLimitCounter.COLLECTION);
        mongo.remove(new Query(), CreditLedger.COLLECTION);
    }

    @AfterEach
    void everyResponseKeepsTheInvariantsAndTheFrontendContract() {
        LimiterClock.INSTANCE.release();
        try {
            invariants.verifyAndReset();
        } finally {
            frontend.verifyAndReset();
        }
    }

    // --- data -------------------------------------------------------------------------------------

    protected Workspace newWorkspace() {
        return workspaces.save(TestWorkspaces.builder().build());
    }

    protected Workspace newWorkspace(TestWorkspaces.Builder builder) {
        return workspaces.save(builder.build());
    }

    /** A documentation-range client IP, fresh per call. */
    protected static String newIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "198.51." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected static Map<String, Object> body(String cnpj, String responsavel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cnpj", cnpj);
        body.put("responsavel", responsavel);
        return body;
    }

    protected LinkCase caseOf(String token) {
        return mongo.findOne(query(where(LinkCase.TOKEN_HASH).is(TestCases.hash(token))), LinkCase.class);
    }

    protected List<LinkCase> casesOf(Workspace workspace) {
        return mongo.find(query(where(LinkCase.WORKSPACE_ID).is(workspace.id())), LinkCase.class);
    }

    protected List<AuditEvent> auditsOf(Workspace workspace) {
        return mongo.find(query(where(AuditEvent.WORKSPACE_ID).is(workspace.id())), AuditEvent.class);
    }

    protected List<Seller> sellersOf(Workspace workspace) {
        return mongo.find(query(where(Seller.WORKSPACE_ID).is(workspace.id())), Seller.class);
    }

    protected Optional<Verification> verificationOf(LinkCase linkCase) {
        return Optional.ofNullable(mongo.findOne(query(where(Verification.CASE_ID).is(linkCase.id())),
                Verification.class));
    }

    // --- requests ---------------------------------------------------------------------------------

    protected RequestSpecification from(String ip) {
        return http().header(CLIENT_IP_HEADER, ip);
    }

    protected Response info(String slug) {
        return http().get("/public/v/" + slug);
    }

    protected Response lookup(String slug, String cnpj, String ip) {
        return from(ip).urlEncodingEnabled(true).queryParam("cnpj", cnpj).get("/public/v/" + slug + "/lookup");
    }

    protected Response verifyBody(String slug, Object body, String ip, String key) {
        freeProviderGate();
        return from(ip).header(IDEMPOTENCY_KEY, key).contentType(ContentType.JSON).body(body)
                .post("/public/v/" + slug + "/verify");
    }

    protected Response verify(String slug, String cnpj, String responsavel, String ip) {
        return verifyBody(slug, body(cnpj, responsavel), ip, newKey());
    }

    /**
     * Frees the provider's per-minute call gate. The test profile keeps the placeholder plan rate
     * (60 calls a minute) and runs no recovery poller, so a verification the gate defers would never
     * be picked up again; tests that wait for an outcome start from a free gate. A burst inside the
     * rate is exercised without freeing it, and the gate itself where the poller runs.
     */
    protected void freeProviderGate() {
        mongo.remove(query(where("_id").regex("^provider\\.cnpja:")), RateLimitCounter.COLLECTION);
    }

    /** Starts a verification from a fresh client and returns its token. */
    protected String start(Workspace workspace, String cnpj, String responsavel) {
        freeProviderGate();
        Response response = verify(workspace.slug(), cnpj, responsavel, newIp());
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        return response.jsonPath().getString("token");
    }

    protected Response readCase(String slug, String token, String ip) {
        return from(ip).get("/public/v/" + slug + "/cases/" + token);
    }

    protected Response putContact(String slug, String token, Object body, String ip) {
        return from(ip).contentType(ContentType.JSON).body(body).put("/public/v/" + slug + "/cases/" + token + "/contact");
    }

    protected Response putEmail(String slug, String token, String email, String ip) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        return putContact(slug, token, body, ip);
    }

    protected static Map<String, Object> json(Response response) {
        return response.jsonPath().getMap("");
    }

    // --- outcomes ---------------------------------------------------------------------------------

    /** Every case read made while waiting, the last one, and the case as stored at the end. */
    protected record Outcome(List<Response> reads, LinkCase linkCase) {
        Response last() {
            return reads.getLast();
        }

        Map<String, Object> body() {
            return json(last());
        }

        String estado() {
            return last().jsonPath().getString("estado");
        }
    }

    /**
     * Polls the case read every 100 ms until the seller sees a decision or the worker has settled
     * the case (verdict written or handed to a person), then returns every read and the stored case.
     */
    protected Outcome awaitOutcome(Workspace workspace, String token) {
        return awaitUntil(workspace, token, linkCase -> linkCase != null && linkCase.processamento() != null
                && SETTLED.contains(linkCase.processamento().estado()));
    }

    protected Outcome awaitUntil(Workspace workspace, String token, Predicate<LinkCase> settled) {
        return awaitUntil(workspace, token, settled, OUTCOME_TIMEOUT);
    }

    protected Outcome awaitUntil(Workspace workspace, String token, Predicate<LinkCase> settled, Duration timeout) {
        String ip = newIp();
        List<Response> reads = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Response read = readCase(workspace.slug(), token, ip);
            assertThat(read.statusCode()).as(read.asString()).isEqualTo(200);
            reads.add(read);
            LinkCase stored = caseOf(token);
            if (settled.test(stored)) {
                Response last = readCase(workspace.slug(), token, ip);
                reads.add(last);
                return new Outcome(reads, caseOf(token));
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the case never settled; last read " + read.asString() + ", stored "
                        + (stored == null ? null : stored.processamento()));
            }
            sleep(Duration.ofMillis(100));
        }
    }

    protected static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // --- assertions -------------------------------------------------------------------------------

    /** Status, JSON content type and exactly {@code {code, message[, field]}} with the code's own message. */
    protected static Map<String, Object> assertError(Response response, int status, String code) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        assertThat(response.contentType()).startsWith("application/json");
        Map<String, Object> body = json(response);
        assertThat(body.keySet()).as(response.asString()).contains("code", "message")
                .isSubsetOf("code", "message", "field");
        assertThat(body.get("code")).isEqualTo(code);
        assertThat(body.get("message")).isEqualTo(ErrorCode.valueOf(code).message());
        return body;
    }

    protected static void assertError(Response response, int status, String code, String field) {
        assertThat(assertError(response, status, code)).containsEntry("field", field);
    }

    /** A digits-only {@code Retry-After} between 1 and {@code max} seconds. */
    protected static void assertRetryAfterAtMost(Response response, int max) {
        String retryAfter = response.header("Retry-After");
        assertThat(retryAfter).as("Retry-After").isNotNull().matches("^\\d+$");
        assertThat(Integer.parseInt(retryAfter)).isBetween(1, max);
    }

    // --- metrics ----------------------------------------------------------------------------------

    /** The sum of every sample of {@code name} whose labels contain all of {@code labels}. */
    protected double metric(String name, String... labels) {
        String body = RestAssured.given().port(managementPort).get("/actuator/prometheus").asString();
        Pattern line = Pattern.compile("^" + Pattern.quote(name) + "(?:\\{([^}]*)\\})? ([0-9.eE+-]+)$",
                Pattern.MULTILINE);
        Matcher m = line.matcher(body);
        double total = 0;
        while (m.find()) {
            String found = m.group(1) == null ? "" : m.group(1);
            boolean all = true;
            for (String label : labels) {
                all &= found.contains(label);
            }
            if (all) {
                total += Double.parseDouble(m.group(2));
            }
        }
        return total;
    }

    protected static Instant now() {
        return Instant.now();
    }
}
