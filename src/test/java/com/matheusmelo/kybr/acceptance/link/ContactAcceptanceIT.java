package com.matheusmelo.kybr.acceptance.link;

import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.NOT_FOUND;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.web.LimiterClock;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The seller's contact e-mail (E05): storage, masking, audit and outbox, refusals, order and limits. */
@ExtendWith(OutputCaptureExtension.class)
class ContactAcceptanceIT extends LinkAcceptanceTest {

    private static final String JOAO = "joao@empresa-exemplo.test";
    private static final String OUTRO = "outro.endereco@empresa-exemplo.test";

    private TestCases.Built saved(Workspace workspace, EstadoCaso estado) {
        TestCases.Built built = TestCases.builder(workspace).estado(estado).build();
        cases.save(built.linkCase());
        return built;
    }

    private List<AuditEvent> contactAudits(Workspace workspace) {
        return auditsOf(workspace).stream().filter(e -> e.tipo() == TipoAuditoria.CONTATO_ALTERADO).toList();
    }

    private List<OutboxEvent> outboxOf(LinkCase linkCase) {
        return mongo.find(query(where(OutboxEvent.CASE_ID).is(linkCase.id())), OutboxEvent.class);
    }

    private void assertNoCollectionContains(String... values) {
        for (String collection : mongo.getCollectionNames()) {
            for (Document doc : mongo.getCollection(collection).find()) {
                String text = doc.toJson();
                for (String value : values) {
                    assertThat(text).as(collection + " holds a raw address").doesNotContain(value);
                }
            }
        }
    }

    @Test
    @DisplayName("an e-mail on a processing case is 204, then shown masked on the case read")
    void anEmailIsSavedAndShownMasked() {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace, EstadoCaso.processing);

        Response response = putEmail(workspace.slug(), built.token(), JOAO, newIp());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.asByteArray()).isEmpty();
        Response read = readCase(workspace.slug(), built.token(), newIp());
        assertThat(read.jsonPath().getMap("resultado")).isEqualTo(Map.of("responsavel", "Maria Exemplo",
                "email_contato_mascarado", "j•••@empresa-exemplo.test"));
    }

    @Test
    @DisplayName("the first address is audited only; a change is audited and queues one encrypted notice")
    void aChangeIsAuditedAndQueued() {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace, EstadoCaso.processing);
        String ip = newIp();

        putEmail(workspace.slug(), built.token(), JOAO, ip);
        assertThat(contactAudits(workspace)).hasSize(1);
        assertThat(outboxOf(built.linkCase())).as("no notice for a first address").isEmpty();
        putEmail(workspace.slug(), built.token(), OUTRO, ip);

        List<AuditEvent> audits = contactAudits(workspace);
        assertThat(audits).hasSize(2);
        AuditEvent change = audits.stream().filter(a -> a.resumo().anteriorMascarado() != null).findFirst().orElseThrow();
        assertThat(change.resumo().anteriorMascarado()).isEqualTo("j•••@empresa-exemplo.test");
        assertThat(change.resumo().novoMascarado()).isEqualTo("o•••@empresa-exemplo.test");
        assertThat(change.clientIp()).isEqualTo(ip);
        assertThat(outboxOf(built.linkCase())).singleElement()
                .satisfies(event -> assertThat(event.payloadCifrado()).isNotEmpty());
        assertNoCollectionContains(JOAO, OUTRO);
    }

    @Test
    @DisplayName("the same address again changes nothing")
    void theSameAddressChangesNothing() {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace, EstadoCaso.processing);
        putEmail(workspace.slug(), built.token(), JOAO, newIp());

        Response again = putEmail(workspace.slug(), built.token(), JOAO, newIp());
        Response padded = putEmail(workspace.slug(), built.token(), "  " + JOAO + "  ", newIp());

        assertThat(again.statusCode()).isEqualTo(204);
        assertThat(padded.statusCode()).isEqualTo(204);
        assertThat(contactAudits(workspace)).hasSize(1);
        assertThat(outboxOf(built.linkCase())).isEmpty();
    }

    @Test
    @DisplayName("an address differing only in letter case changes nothing and costs no change")
    void aCaseOnlyDifferenceChangesNothing() {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace, EstadoCaso.processing);
        putEmail(workspace.slug(), built.token(), JOAO, newIp());
        LimiterClock.INSTANCE.freeze(Instant.now().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofHours(12)));

        for (int i = 0; i < 6; i++) {
            assertThat(putEmail(workspace.slug(), built.token(), i % 2 == 0 ? JOAO.toUpperCase() : "Joao@Empresa-Exemplo.TEST",
                    newIp()).statusCode()).isEqualTo(204);
        }

        assertThat(contactAudits(workspace)).hasSize(1);
        assertThat(outboxOf(built.linkCase())).isEmpty();
        assertThat(caseOf(built.token()).contato().emailMascarado()).isEqualTo("j•••@empresa-exemplo.test");
        assertThat(putEmail(workspace.slug(), built.token(), OUTRO, newIp()).statusCode())
                .as("no daily change was consumed").isEqualTo(204);
    }

    @Test
    @DisplayName("a decided case is 409; pending, processing and no-data cases accept an address")
    void decidedCasesRefuseAnAddress() {
        Workspace workspace = newWorkspace();

        for (EstadoCaso decided : List.of(EstadoCaso.approved, EstadoCaso.blocked)) {
            assertError(putEmail(workspace.slug(), saved(workspace, decided).token(), JOAO, newIp()), 409,
                    "CASO_DECIDIDO");
        }
        for (EstadoCaso open : List.of(EstadoCaso.pending, EstadoCaso.processing)) {
            assertThat(putEmail(workspace.slug(), saved(workspace, open).token(), JOAO, newIp()).statusCode())
                    .isEqualTo(204);
        }
        String noData = start(workspace, NOT_FOUND, PARTNER);
        awaitOutcome(workspace, noData);
        assertThat(putEmail(workspace.slug(), noData, JOAO, newIp()).statusCode()).isEqualTo(204);
        assertThat(readCase(workspace.slug(), noData, newIp()).jsonPath().getMap("resultado")).isEqualTo(Map.of(
                "responsavel", PARTNER, "variante", "DADOS_NAO_ENCONTRADOS",
                "email_contato_mascarado", "j•••@empresa-exemplo.test"));
    }

    @ParameterizedTest(name = "email=[{0}] is 422 EMAIL_INVALIDO")
    @ValueSource(strings = {"a​b@x.test", "bell\u0007@x.test", "a b@x.test", "a@@x.test", "a@x", "", "   "})
    void aRefusedAddressIs422(String email) {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace, EstadoCaso.processing);

        assertError(putEmail(workspace.slug(), built.token(), email, newIp()), 422, "EMAIL_INVALIDO", "email");
    }

    @Test
    @DisplayName("too long, missing, null and non-string addresses are 422; edge forms are accepted")
    void lengthMissingAndAcceptedForms() {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace, EstadoCaso.processing);
        String slug = workspace.slug();
        String tooLong = "a".repeat(255 - "@x.test".length()) + "@x.test";

        assertError(putEmail(slug, built.token(), tooLong, newIp()), 422, "EMAIL_INVALIDO", "email");
        assertError(putContact(slug, built.token(), "{}", newIp()), 422, "EMAIL_INVALIDO", "email");
        assertError(putContact(slug, built.token(), "{\"email\":null}", newIp()), 422, "EMAIL_INVALIDO", "email");
        assertError(putContact(slug, built.token(), "{\"email\":42}", newIp()), 422, "EMAIL_INVALIDO", "email");
        assertError(putContact(slug, built.token(), "{\"email\":\"a@b.co\",\"x\":1}", newIp()), 422, "VALOR_INVALIDO");
        assertThat(putEmail(slug, built.token(), "a@b.co", newIp()).statusCode()).isEqualTo(204);
        assertThat(putEmail(slug, built.token(), "\"<x>\"@x.test", newIp()).statusCode()).isEqualTo(204);
        assertThat(putEmail(slug, built.token(), "a".repeat(254 - "@x.test".length()) + "@x.test", newIp())
                .statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("the order: slug, case, address, then decided; the not-found answers are identical")
    void theCheckOrder() {
        Workspace workspace = newWorkspace();
        TestCases.Built approved = saved(workspace, EstadoCaso.approved);
        TestCases.Built foreign = saved(newWorkspace(), EstadoCaso.processing);

        assertError(putEmail("loja-desconhecida-qa", approved.token(), "bad", newIp()), 404, "LINK_NAO_ENCONTRADO");
        Response unknown = putEmail(workspace.slug(), TestCases.newToken(), "bad", newIp());
        Response other = putEmail(workspace.slug(), foreign.token(), "bad", newIp());
        assertError(unknown, 404, "CASO_NAO_ENCONTRADO");
        assertThat(other.asByteArray()).isEqualTo(unknown.asByteArray());
        assertError(putEmail(workspace.slug(), approved.token(), "bad", newIp()), 422, "EMAIL_INVALIDO", "email");
        assertError(putEmail(workspace.slug(), approved.token(), JOAO, newIp()), 409, "CASO_DECIDIDO");
    }

    @Test
    @DisplayName("the raw address never reaches a log or a response, and a damaged ciphertext never breaks a read")
    void theRawAddressStaysOutOfLogsAndADamagedCiphertextIsHarmless(CapturedOutput output) {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace, EstadoCaso.processing);
        String canary = "canario.qa.contato@empresa-exemplo.test";

        Response put = putEmail(workspace.slug(), built.token(), canary, newIp());
        Document doc = mongo.getCollection(LinkCase.COLLECTION).find(new Document("_id", built.linkCase().id())).first();
        byte[] cipher = ((Binary) ((Document) doc.get("contato")).get("email_cifrado")).getData();
        cipher[cipher.length - 1] ^= 0x01;
        mongo.getCollection(LinkCase.COLLECTION).updateOne(new Document("_id", built.linkCase().id()),
                new Document("$set", new Document("contato.email_cifrado", new Binary(cipher))));
        Response read = readCase(workspace.slug(), built.token(), newIp());
        Response change = putEmail(workspace.slug(), built.token(), OUTRO, newIp());

        assertThat(put.statusCode()).isEqualTo(204);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.jsonPath().getString("resultado.email_contato_mascarado")).isEqualTo("c•••@empresa-exemplo.test");
        assertThat(change.statusCode()).isLessThan(500);
        assertThat(output.getAll()).doesNotContain(canary).doesNotContain(OUTRO);
        assertNoCollectionContains(canary);
    }

    @Test
    @DisplayName("without a client IP the audit records the IP as unknown")
    void withoutAClientIpTheAuditSaysUnknown() {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace, EstadoCaso.processing);

        Response response = http().contentType(ContentType.JSON).body(Map.of("email", JOAO))
                .put("/public/v/" + workspace.slug() + "/cases/" + built.token() + "/contact");

        assertThat(response.statusCode()).isEqualTo(204);
        AuditEvent audit = contactAudits(workspace).getFirst();
        assertThat(audit.ipDesconhecido()).isTrue();
        assertThat(audit.clientIp()).isNull();
    }

    @Test
    @DisplayName("parallel changes never fail, and the stored address is one of them")
    void parallelChangesNeverFail() throws Exception {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace, EstadoCaso.processing);
        List<String> addresses = List.of("a1@empresa-exemplo.test", "b2@empresa-exemplo.test",
                "c3@empresa-exemplo.test", "d4@empresa-exemplo.test", "e5@empresa-exemplo.test");

        List<Response> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Response>> futures = new ArrayList<>();
            for (String address : addresses) {
                futures.add(pool.submit(() -> putEmail(workspace.slug(), built.token(), address, newIp())));
            }
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
        }

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).as(r.asString()).isEqualTo(204));
        String masked = caseOf(built.token()).contato().emailMascarado();
        assertThat(masked).isIn("a•••@empresa-exemplo.test", "b•••@empresa-exemplo.test", "c•••@empresa-exemplo.test",
                "d•••@empresa-exemplo.test", "e•••@empresa-exemplo.test");
        assertThat(contactAudits(workspace)).hasSizeBetween(1, 5);
        assertThat(outboxOf(built.linkCase()).stream().filter(OutboxEvent::pendente).count()).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a one-character local part masks to just the bullets")
    void aOneCharacterLocalPartMasksToBullets() {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace, EstadoCaso.processing);

        putEmail(workspace.slug(), built.token(), "a@x.test", newIp());

        assertThat(readCase(workspace.slug(), built.token(), newIp()).jsonPath()
                .getString("resultado.email_contato_mascarado")).isEqualTo("•••@x.test");
    }

    @Test
    @DisplayName("the sixth address change on a case in a day is 429, and notices stay coalesced")
    void thePerCaseChangeLimit() {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace, EstadoCaso.processing);
        LimiterClock.INSTANCE.freeze(Instant.now().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofHours(12)));

        for (int i = 0; i < 5; i++) {
            assertThat(putEmail(workspace.slug(), built.token(), "troca" + i + "@empresa-exemplo.test", newIp())
                    .statusCode()).as("change " + (i + 1)).isEqualTo(204);
        }
        Response limited = putEmail(workspace.slug(), built.token(), "troca9@empresa-exemplo.test", newIp());
        Response unchanged = putEmail(workspace.slug(), built.token(), "troca4@empresa-exemplo.test", newIp());

        assertError(limited, 429, "LIMITE_TENTATIVAS");
        assertRetryAfterAtMost(limited, 86_400);
        assertThat(unchanged.statusCode()).as("the same address costs no change").isEqualTo(204);
        assertThat(outboxOf(built.linkCase()).stream().filter(OutboxEvent::pendente).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the 21st contact request from one client in an hour is 429")
    void thePerClientHourlyLimit() {
        Workspace workspace = newWorkspace();
        TestCases.Built built = saved(workspace, EstadoCaso.processing);
        LimiterClock.INSTANCE.freeze(Instant.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(2))
                .plus(Duration.ofMinutes(30)));
        String ip = newIp();

        for (int i = 0; i < 20; i++) {
            assertThat(putEmail(workspace.slug(), built.token(), JOAO, ip).statusCode()).isEqualTo(204);
        }
        Response limited = putEmail(workspace.slug(), built.token(), JOAO, ip);

        assertError(limited, 429, "LIMITE_TENTATIVAS");
        assertRetryAfterAtMost(limited, 3600);
    }
}
