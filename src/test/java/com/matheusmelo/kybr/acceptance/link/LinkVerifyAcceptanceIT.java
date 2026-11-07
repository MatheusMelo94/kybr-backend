package com.matheusmelo.kybr.acceptance.link;

import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.APPROVED;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTNER;
import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.acceptance.link.fixtures.Cnpjs;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OrigemSeller;
import com.matheusmelo.kybr.model.link.Seller;
import com.matheusmelo.kybr.model.link.StatusSeller;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.LimiterClock;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Starting a verification (E03): the case, validation, idempotency, limits and their order. */
class LinkVerifyAcceptanceIT extends LinkAcceptanceTest {

    private static final String PRINTABLE_EDGES = "~`\"\\ ok";

    private static Instant midWindow() {
        // Ten-minute and one-hour windows: well inside both, and ahead of any real-time counter.
        return Instant.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(3)).plus(Duration.ofMinutes(5));
    }

    private Map<String, Object> bodyWith(String cnpj, String responsavel, String ref, String extId) {
        Map<String, Object> body = body(cnpj, responsavel);
        body.put("ref", ref);
        body.put("ext_id", extId);
        return body;
    }

    @Test
    @DisplayName("a valid request is 201 with exactly an opaque token, echoing nothing")
    void aValidRequestReturnsOnlyAToken() {
        Workspace workspace = newWorkspace();

        Response response = verifyBody(workspace.slug(), bodyWith(APPROVED, PARTNER, "pedido-qa-ref-01", "ext-qa-id-01"),
                newIp(), newKey());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response)).containsOnlyKeys("token");
        assertThat(response.jsonPath().getString("token")).matches("^[A-Za-z0-9_-]{22,64}$").hasSize(43);
        assertThat(response.asString()).doesNotContain("pedido-qa-ref-01", "ext-qa-id-01");
        assertThat(response.headers().toString()).doesNotContain("pedido-qa-ref-01", "ext-qa-id-01");
        assertThat(response.header("Location")).isNull();
    }

    @Test
    @DisplayName("the case, the seller and the audit event are stored, with only the token's hash")
    void theCaseSellerAndAuditAreStored() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        String ref = PRINTABLE_EDGES + "r".repeat(256 - PRINTABLE_EDGES.length());
        String ip = newIp();

        freeProviderGate();
        Response response = verifyBody(workspace.slug(), bodyWith(cnpj, PARTNER, ref, PRINTABLE_EDGES), ip, newKey());
        String token = response.jsonPath().getString("token");
        Outcome outcome = awaitOutcome(workspace, token);

        LinkCase stored = outcome.linkCase();
        assertThat(stored.tokenHash()).isEqualTo(TestCases.hash(token));
        assertThat(stored.cnpj()).isEqualTo(cnpj);
        assertThat(stored.ref()).isEqualTo(ref);
        assertThat(stored.extId()).isEqualTo(PRINTABLE_EDGES);
        assertThat(stored.politica()).isEqualTo(workspace.politica());
        Document raw = mongo.getCollection(LinkCase.COLLECTION).find(new Document("_id", stored.id())).first();
        assertThat(raw).isNotNull();
        assertThat(raw.toJson()).doesNotContain(token);
        List<Seller> sellers = sellersOf(workspace);
        assertThat(sellers).singleElement().satisfies(seller -> {
            assertThat(seller.origem()).isEqualTo(OrigemSeller.link);
            assertThat(seller.status()).isEqualTo(StatusSeller.em_verificacao);
            assertThat(seller.cnpj()).isEqualTo(cnpj);
        });
        AuditEvent audit = auditsOf(workspace).stream().filter(e -> e.tipo() == TipoAuditoria.VERIFICACAO_INICIADA)
                .findFirst().orElseThrow();
        assertThat(audit.caseId()).isEqualTo(stored.id());
        assertThat(audit.ator()).isEqualTo(Ator.vendedor);
        assertThat(audit.clientIp()).isEqualTo(ip);
        assertThat(audit.ipDesconhecido()).isFalse();
        assertThat(audit.sessionHash()).isNull();
        assertThat(audit.resumo().cnpjMascarado()).isEqualTo(cnpj.substring(0, 2) + ".***.***/****-" + cnpj.substring(12));
        assertThat(audit.resumo().novoMascarado()).isNull();
        assertThat(mongo.getCollection(AuditEvent.COLLECTION).find(new Document("_id", audit.id())).first().toJson())
                .doesNotContain(cnpj);
    }

    @Test
    @DisplayName("without a client IP the audit event records the IP as unknown")
    void withoutAClientIpTheAuditSaysUnknown() {
        Workspace workspace = newWorkspace();

        Response response = http().header(IDEMPOTENCY_KEY, newKey()).contentType(ContentType.JSON)
                .body(body(Cnpjs.unknownToTheStub(), PARTNER)).post("/public/v/" + workspace.slug() + "/verify");

        assertThat(response.statusCode()).isEqualTo(201);
        AuditEvent audit = auditsOf(workspace).getFirst();
        assertThat(audit.ipDesconhecido()).isTrue();
        assertThat(audit.clientIp()).isNull();
    }

    @ParameterizedTest(name = "cnpj=[{0}] is 422 CNPJ_INVALIDO")
    @ValueSource(strings = {"12ABC34501DE36", "12ABC34501DE3A", "00000000000000", "12abc34501de35",
        "12.345.678/0001-95", "", "1234567800019", "123456780001950"})
    void anInvalidCnpjIs422(String cnpj) {
        Workspace workspace = newWorkspace();

        assertError(verify(workspace.slug(), cnpj, PARTNER, newIp()), 422, "CNPJ_INVALIDO", "cnpj");
        assertThat(casesOf(workspace)).isEmpty();
    }

    @Test
    @DisplayName("a CNPJ sent as a JSON number is 422 CNPJ_INVALIDO")
    void aNumericCnpjIs422() {
        Workspace workspace = newWorkspace();

        Response response = verifyBody(workspace.slug(), "{\"cnpj\":12345678000195,\"responsavel\":\"Maria Exemplo\"}",
                newIp(), newKey());

        assertError(response, 422, "CNPJ_INVALIDO", "cnpj");
        assertThat(casesOf(workspace)).isEmpty();
    }

    static Stream<Arguments> refusedNames() {
        return Stream.of(
                Arguments.of("one word", "Maria"),
                Arguments.of("121 code points", "Ana " + "a".repeat(117)),
                Arguments.of("digits", "Maria 2 Silva"),
                Arguments.of("angle bracket", "Maria <Silva>"),
                Arguments.of("Hangul filler U+3164", "Maria ㅤ Silva"),
                Arguments.of("Hangul filler U+FFA0", "MariaﾠSilva Souza"),
                Arguments.of("Hangul filler U+115F", "Maria ᅟ Silva"),
                Arguments.of("Hangul filler U+1160", "Maria ᅠ Silva"),
                Arguments.of("three combining marks", "Maria Q" + "q́̂̃" + " Silva"),
                Arguments.of("right-to-left override", "Maria ‮Silva"));
    }

    @ParameterizedTest(name = "responsavel with {0} is 422 RESPONSAVEL_INVALIDO")
    @MethodSource("refusedNames")
    void aRefusedNameIs422(String why, String responsavel) {
        Workspace workspace = newWorkspace();

        assertError(verify(workspace.slug(), APPROVED, responsavel, newIp()), 422, "RESPONSAVEL_INVALIDO",
                "responsavel");
        assertThat(casesOf(workspace)).isEmpty();
    }

    static Stream<Arguments> acceptedNames() {
        return Stream.of(
                Arguments.of("120 code points", "Ana " + "a".repeat(116), "Ana " + "a".repeat(116)),
                Arguments.of("a decomposed accent", "José Silva", "José Silva"),
                Arguments.of("surrounding spaces", "  Maria Silva  ", "Maria Silva"),
                Arguments.of("apostrophe and hyphen", "Ana O'Neil-Souza", "Ana O'Neil-Souza"),
                Arguments.of("120 code points after NFC", "Ana " + "é".repeat(116), "Ana " + "é".repeat(116)));
    }

    @ParameterizedTest(name = "responsavel with {0} is accepted and stored normalised")
    @MethodSource("acceptedNames")
    void anAcceptedNameIsStoredNormalised(String why, String responsavel, String stored) {
        Workspace workspace = newWorkspace();

        Response response = verify(workspace.slug(), Cnpjs.unknownToTheStub(), responsavel, newIp());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        assertThat(caseOf(response.jsonPath().getString("token")).responsavel()).isEqualTo(stored);
    }

    @ParameterizedTest(name = "{0}=[{1}] is 422 VALOR_INVALIDO")
    @MethodSource("refusedOptionalValues")
    void aRefusedOptionalValueIs422(String field, String value) {
        Workspace workspace = newWorkspace();
        Map<String, Object> body = body(APPROVED, PARTNER);
        body.put(field, value);

        assertError(verifyBody(workspace.slug(), body, newIp(), newKey()), 422, "VALOR_INVALIDO", field);
    }

    static Stream<Arguments> refusedOptionalValues() {
        List<Arguments> all = new ArrayList<>();
        for (String field : List.of("ref", "ext_id")) {
            for (String value : List.of("x".repeat(257), "", "bell\u0007", "acentuação")) {
                all.add(Arguments.of(field, value));
            }
        }
        return all.stream();
    }

    @Test
    @DisplayName("absent or null ref and ext_id are accepted")
    void absentOrNullOptionalValuesAreAccepted() {
        Workspace workspace = newWorkspace();
        Map<String, Object> withNulls = body(Cnpjs.unknownToTheStub(), PARTNER);
        withNulls.put("ref", null);
        withNulls.put("ext_id", null);

        assertThat(verifyBody(workspace.slug(), body(Cnpjs.unknownToTheStub(), PARTNER), newIp(), newKey()).statusCode())
                .isEqualTo(201);
        assertThat(verifyBody(workspace.slug(), withNulls, newIp(), newKey()).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("missing or null fields get their own code; an unknown property is VALOR_INVALIDO")
    void missingNullAndUnknownFields() {
        Workspace workspace = newWorkspace();
        String slug = workspace.slug();

        assertError(verifyBody(slug, "{\"responsavel\":\"Maria Exemplo\"}", newIp(), newKey()), 422, "CNPJ_INVALIDO", "cnpj");
        assertError(verifyBody(slug, "{\"cnpj\":null,\"responsavel\":\"Maria Exemplo\"}", newIp(), newKey()), 422,
                "CNPJ_INVALIDO", "cnpj");
        assertError(verifyBody(slug, "{\"cnpj\":\"" + APPROVED + "\"}", newIp(), newKey()), 422, "RESPONSAVEL_INVALIDO",
                "responsavel");
        assertError(verifyBody(slug, "{\"cnpj\":\"" + APPROVED + "\",\"responsavel\":null}", newIp(), newKey()), 422,
                "RESPONSAVEL_INVALIDO", "responsavel");
        assertError(verifyBody(slug, "{\"cnpj\":\"" + APPROVED + "\",\"responsavel\":42}", newIp(), newKey()), 422,
                "RESPONSAVEL_INVALIDO", "responsavel");
        assertError(verifyBody(slug, "{}", newIp(), newKey()), 422, "CNPJ_INVALIDO", "cnpj");
        Map<String, Object> unknown = body(APPROVED, PARTNER);
        unknown.put("turnstile_token", "x");
        assertError(verifyBody(slug, unknown, newIp(), newKey()), 422, "VALOR_INVALIDO");
        assertThat(casesOf(workspace)).isEmpty();
    }

    @Test
    @DisplayName("the slug is checked before the body, and the first invalid field is reported")
    void theSlugComesFirstThenTheFirstInvalidField() {
        Workspace workspace = newWorkspace();

        assertError(verify("loja-desconhecida-qa", "123", "x", newIp()), 404, "LINK_NAO_ENCONTRADO");
        assertError(verify(workspace.slug(), "123", "x", newIp()), 422, "CNPJ_INVALIDO", "cnpj");
        assertError(verifyBody(workspace.slug(), bodyWith(APPROVED, "x", "", "")), 422, "RESPONSAVEL_INVALIDO",
                "responsavel");
        assertError(verifyBody(workspace.slug(), bodyWith(APPROVED, PARTNER, "", "")), 422, "VALOR_INVALIDO", "ref");
    }

    private Response verifyBody(String slug, Map<String, Object> body) {
        return verifyBody(slug, body, newIp(), newKey());
    }

    @Test
    @DisplayName("an unknown, disabled or uncontacted link is 404 and writes nothing")
    void anUnservableLinkIsNotFoundAndWritesNothing() {
        Workspace disabled = newWorkspace(TestWorkspaces.builder().linkAtivo(false));
        Workspace uncontacted = newWorkspace(TestWorkspaces.builder().contato(null));

        for (String slug : List.of("loja-desconhecida-qa", disabled.slug(), uncontacted.slug())) {
            assertError(verify(slug, APPROVED, PARTNER, newIp()), 404, "LINK_NAO_ENCONTRADO");
        }
        assertThat(casesOf(disabled)).isEmpty();
        assertThat(casesOf(uncontacted)).isEmpty();
        assertThat(auditsOf(disabled)).isEmpty();
        assertThat(auditsOf(uncontacted)).isEmpty();
    }

    @Test
    @DisplayName("an exact replay answers the same bytes, creates nothing and costs no CNPJ attempt")
    void anExactReplayIsFree() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        String key = newKey();
        String ip = newIp();
        LimiterClock.INSTANCE.freeze(midWindow());

        Response original = verifyBody(workspace.slug(), body(cnpj, PARTNER), ip, key);
        Response replay1 = verifyBody(workspace.slug(), body(cnpj, PARTNER), ip, key);
        Response replay2 = verifyBody(workspace.slug(), body(cnpj, PARTNER), newIp(), key);

        assertThat(original.statusCode()).isEqualTo(201);
        assertThat(replay1.asByteArray()).isEqualTo(original.asByteArray());
        assertThat(replay2.asByteArray()).isEqualTo(original.asByteArray());
        assertThat(replay1.statusCode()).isEqualTo(201);
        assertThat(casesOf(workspace)).hasSize(1);
        assertThat(verify(workspace.slug(), cnpj, PARTNER, newIp()).statusCode()).isEqualTo(201);
        assertThat(verify(workspace.slug(), cnpj, PARTNER, newIp()).statusCode()).isEqualTo(201);
        assertError(verify(workspace.slug(), cnpj, PARTNER, newIp()), 429, "LIMITE_CNPJ");
    }

    @Test
    @DisplayName("a replay after the case was decided still answers the original 201")
    void aReplayAfterTheDecisionIsTheOriginalAnswer() {
        Workspace workspace = newWorkspace();
        String key = newKey();
        Response original = verifyBody(workspace.slug(), body(APPROVED, PARTNER), newIp(), key);
        Outcome outcome = awaitOutcome(workspace, original.jsonPath().getString("token"));
        assertThat(outcome.linkCase().estado()).isEqualTo(EstadoCaso.approved);

        Response replay = verifyBody(workspace.slug(), body(APPROVED, PARTNER), newIp(), key);

        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.asByteArray()).isEqualTo(original.asByteArray());
    }

    @Test
    @DisplayName("a not-found answer is replayed for the key's lifetime, as the contract states")
    void aNotFoundAnswerIsReplayedAsDocumented() {
        Workspace disabled = newWorkspace(TestWorkspaces.builder().linkAtivo(false));
        String key = newKey();
        Map<String, Object> body = body(Cnpjs.unknownToTheStub(), PARTNER);

        Response first = verifyBody(disabled.slug(), body, newIp(), key);
        workspaces.save(CaseReadAcceptanceIT.withLink(disabled, true));
        Response sameKey = verifyBody(disabled.slug(), body, newIp(), key);
        Response freshKey = verifyBody(disabled.slug(), body, newIp(), newKey());

        assertError(first, 404, "LINK_NAO_ENCONTRADO");
        assertThat(sameKey.asByteArray()).isEqualTo(first.asByteArray());
        assertThat(freshKey.statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("the same key with a different body is 422 on Idempotency-Key and creates nothing")
    void theSameKeyWithAnotherBodyIs422() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        String key = newKey();
        verifyBody(workspace.slug(), body(cnpj, PARTNER), newIp(), key);

        Response mismatch = verifyBody(workspace.slug(), body(cnpj, "Maria Exemplo Outra"), newIp(), key);

        assertError(mismatch, 422, "VALOR_INVALIDO", "Idempotency-Key");
        assertThat(casesOf(workspace)).hasSize(1);
    }

    @Test
    @DisplayName("the same key and body on another link is a separate case")
    void theSameKeyOnAnotherLinkIsSeparate() {
        Workspace first = newWorkspace();
        Workspace second = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        String key = newKey();

        Response one = verifyBody(first.slug(), body(cnpj, PARTNER), newIp(), key);
        Response two = verifyBody(second.slug(), body(cnpj, PARTNER), newIp(), key);

        assertThat(one.statusCode()).isEqualTo(201);
        assertThat(two.statusCode()).isEqualTo(201);
        assertThat(two.jsonPath().getString("token")).isNotEqualTo(one.jsonPath().getString("token"));
        assertThat(casesOf(first)).hasSize(1);
        assertThat(casesOf(second)).hasSize(1);
    }

    @Test
    @DisplayName("ten parallel requests with one key create one case and one audit event")
    void parallelSameKeyCreatesOneCase() throws Exception {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        String key = newKey();
        String ip = newIp();

        List<Response> responses = parallel(10, () -> verifyBody(workspace.slug(), body(cnpj, PARTNER), ip, key));

        List<Response> created = responses.stream().filter(r -> r.statusCode() == 201).toList();
        assertThat(created).isNotEmpty();
        for (Response response : responses) {
            if (response.statusCode() == 201) {
                assertThat(response.asByteArray()).isEqualTo(created.getFirst().asByteArray());
            } else {
                assertError(response, 503, "INDISPONIVEL");
                assertThat(response.header("Retry-After")).isEqualTo("1");
            }
        }
        assertThat(casesOf(workspace)).hasSize(1);
        assertThat(auditsOf(workspace)).hasSize(1);
    }

    @Test
    @DisplayName("parallel requests with different keys for one CNPJ make three cases and one seller")
    void parallelDifferentKeysMakeOneSeller() throws Exception {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();

        List<Response> responses = parallel(3, () -> verify(workspace.slug(), cnpj, PARTNER, newIp()));

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).as(r.asString()).isEqualTo(201));
        assertThat(casesOf(workspace)).hasSize(3);
        assertThat(sellersOf(workspace)).hasSize(1);
    }

    @Test
    @DisplayName("the fourth attempt for one CNPJ on a link within an hour is 429 LIMITE_CNPJ")
    void theFourthAttemptForOneCnpjIsLimited() {
        Workspace workspace = newWorkspace();
        Workspace other = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        Instant window = midWindow();
        LimiterClock.INSTANCE.freeze(window);

        for (int i = 0; i < 3; i++) {
            assertThat(verify(workspace.slug(), cnpj, PARTNER, newIp()).statusCode()).isEqualTo(201);
        }
        Response limited = verify(workspace.slug(), cnpj, PARTNER, newIp());

        assertError(limited, 429, "LIMITE_CNPJ");
        assertRetryAfterAtMost(limited, 3600);
        assertThat(verify(other.slug(), cnpj, PARTNER, newIp()).statusCode()).isEqualTo(201);
        LimiterClock.INSTANCE.freeze(window.plus(Duration.ofHours(1)));
        assertThat(verify(workspace.slug(), cnpj, PARTNER, newIp()).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("the eleventh verification from one client on one link in ten minutes is 429 LIMITE_TENTATIVAS")
    void theEleventhFromOneClientOnOneLinkIsLimited() {
        Workspace workspace = newWorkspace();
        Workspace other = newWorkspace();
        String ip = newIp();
        LimiterClock.INSTANCE.freeze(midWindow());
        String repeated = Cnpjs.unknownToTheStub();

        for (int i = 0; i < 10; i++) {
            String cnpj = i < 3 ? repeated : Cnpjs.unknownToTheStub();
            assertThat(verify(workspace.slug(), cnpj, PARTNER, ip).statusCode()).as("call " + (i + 1)).isEqualTo(201);
        }
        Response limited = verify(workspace.slug(), Cnpjs.unknownToTheStub(), PARTNER, ip);
        Response cnpjAtItsFourth = verify(workspace.slug(), repeated, PARTNER, ip);

        assertError(limited, 429, "LIMITE_TENTATIVAS");
        assertRetryAfterAtMost(limited, 600);
        assertError(cnpjAtItsFourth, 429, "LIMITE_TENTATIVAS");
        assertThat(verify(workspace.slug(), Cnpjs.unknownToTheStub(), PARTNER, newIp()).statusCode()).isEqualTo(201);
        assertThat(verify(other.slug(), Cnpjs.unknownToTheStub(), PARTNER, ip).statusCode())
                .as("counted per client and link").isEqualTo(201);
    }

    @Test
    @DisplayName("fifty verifications on one link from fifty clients are never blocked by link volume")
    void linkVolumeAlertsAndNeverBlocks() {
        Workspace workspace = newWorkspace();
        double before = metric("kybr_link_verify_total", "slug=\"" + workspace.slug() + "\"", "resultado=\"accepted\"");

        for (int i = 0; i < 50; i++) {
            assertThat(verify(workspace.slug(), Cnpjs.unknownToTheStub(), PARTNER, newIp()).statusCode())
                    .as("call " + (i + 1)).isEqualTo(201);
        }

        assertThat(metric("kybr_link_verify_total", "slug=\"" + workspace.slug() + "\"", "resultado=\"accepted\""))
                .isEqualTo(before + 50);
    }

    @Test
    @DisplayName("a 429 is never replayed: the same key runs again once the window passes")
    void aLimitedAnswerIsNotStored() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        Instant window = midWindow();
        LimiterClock.INSTANCE.freeze(window);
        for (int i = 0; i < 3; i++) {
            verify(workspace.slug(), cnpj, PARTNER, newIp());
        }
        String key = newKey();

        assertError(verifyBody(workspace.slug(), body(cnpj, PARTNER), newIp(), key), 429, "LIMITE_CNPJ");
        LimiterClock.INSTANCE.freeze(window.plus(Duration.ofHours(1)));
        Response again = verifyBody(workspace.slug(), body(cnpj, PARTNER), newIp(), key);

        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(casesOf(workspace)).hasSize(4);
    }

    @Test
    @DisplayName("refused requests (422, 404) consume neither the client nor the CNPJ limit")
    void refusedRequestsConsumeNoLimit() {
        Workspace workspace = newWorkspace();
        String ip = newIp();
        String cnpj = Cnpjs.unknownToTheStub();
        LimiterClock.INSTANCE.freeze(midWindow());

        for (int i = 0; i < 15; i++) {
            assertThat(verify(workspace.slug(), cnpj, "Maria", ip).statusCode()).isEqualTo(422);
        }
        for (int i = 0; i < 5; i++) {
            assertThat(verify("loja-desconhecida-qa", cnpj, PARTNER, ip).statusCode()).isEqualTo(404);
        }
        for (int i = 0; i < 3; i++) {
            assertThat(verify(workspace.slug(), cnpj, PARTNER, ip).statusCode()).as("same CNPJ " + (i + 1))
                    .isEqualTo(201);
        }
        for (int i = 0; i < 7; i++) {
            assertThat(verify(workspace.slug(), Cnpjs.unknownToTheStub(), PARTNER, ip).statusCode()).isEqualTo(201);
        }

        assertError(verify(workspace.slug(), Cnpjs.unknownToTheStub(), PARTNER, ip), 429, "LIMITE_TENTATIVAS");
    }

    @ParameterizedTest(name = "Idempotency-Key [{0}] is 422 on the header")
    @ValueSource(strings = {"not-a-uuid", "00000000-0000-0000-0000-000000000000", "ffffffff-ffff-ffff-ffff-ffffffffffff",
        "12345"})
    void aMalformedKeyIs422(String key) {
        Workspace workspace = newWorkspace();

        assertError(verifyBody(workspace.slug(), body(APPROVED, PARTNER), newIp(), key), 422, "VALOR_INVALIDO",
                "Idempotency-Key");
        assertThat(casesOf(workspace)).isEmpty();
    }

    @Test
    @DisplayName("a missing key is 422, and the header name is case-insensitive")
    void aMissingKeyIs422AndTheHeaderNameIsCaseInsensitive() {
        Workspace workspace = newWorkspace();
        String path = "/public/v/" + workspace.slug() + "/verify";

        Response missing = from(newIp()).contentType(ContentType.JSON).body(body(APPROVED, PARTNER)).post(path);
        Response lower = from(newIp()).header("idempotency-key", newKey()).contentType(ContentType.JSON)
                .body(body(Cnpjs.unknownToTheStub(), PARTNER)).post(path);
        Response upper = from(newIp()).header("IDEMPOTENCY-KEY", newKey()).contentType(ContentType.JSON)
                .body(body(Cnpjs.unknownToTheStub(), PARTNER)).post(path);

        assertError(missing, 422, "VALOR_INVALIDO", "Idempotency-Key");
        assertThat(lower.statusCode()).isEqualTo(201);
        assertThat(upper.statusCode()).isEqualTo(201);
    }

    private static List<Response> parallel(int count, Callable<Response> call) throws Exception {
        List<Response> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(call));
            }
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
        }
        return responses;
    }

}
