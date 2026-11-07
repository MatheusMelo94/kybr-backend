package com.matheusmelo.kybr.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.crypto.FieldCipher;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.response.Response;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** E05: the seller's contact e-mail. */
@ExtendWith(OutputCaptureExtension.class)
class LinkContactIT extends WebIntegrationTest {

    private static final String A = "joao@empresa-exemplo.test";
    private static final String B = "maria@empresa-exemplo.test";
    private static final String C = "ana@empresa-exemplo.test";

    @Autowired
    private WorkspaceRepository workspaces;
    @Autowired
    private LinkCaseRepository cases;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private FieldCipher cipher;
    @Autowired
    private MeterRegistry meters;

    private Workspace workspace;
    private String clientIp;

    @BeforeEach
    void setUp() {
        workspace = workspaces.save(TestWorkspaces.builder().build());
        clientIp = "198.51." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256);
    }

    @Test
    void theFirstAddressIsStoredEncryptedAndMaskedWithAnAuditAndNoOutbox(CapturedOutput output) {
        TestCases.Built built = save(TestCases.builder(workspace));

        Response response = put(workspace.slug(), built.token(), "{\"email\":\"  " + A + " \"}");

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(204);
        assertThat(response.asByteArray()).isEmpty();
        LinkCase stored = cases.findById(built.linkCase().id()).orElseThrow();
        assertThat(stored.contato().emailMascarado()).isEqualTo("j•••@empresa-exemplo.test");
        assertThat(cipher.decrypt(stored.contato().emailCifrado(), stored.id())).isEqualTo(A);
        assertThat(audits(stored)).singleElement().satisfies(event -> {
            assertThat(event.tipo()).isEqualTo(TipoAuditoria.CONTATO_ALTERADO);
            assertThat(event.clientIp()).isEqualTo(clientIp);
            assertThat(event.resumo().anteriorMascarado()).isNull();
            assertThat(event.resumo().novoMascarado()).isEqualTo("j•••@empresa-exemplo.test");
        });
        assertThat(outbox(stored)).isEmpty();
        assertThat(mongo.getCollection(LinkCase.COLLECTION).find().into(new java.util.ArrayList<>()).toString())
                .doesNotContain(A);
        assertThat(output.getAll()).doesNotContain(A);
        assertThat(http().header("X-Kybr-Client-IP", clientIp)
                .get("/public/v/{slug}/cases/{token}", workspace.slug(), built.token())
                .jsonPath().getString("resultado.email_contato_mascarado")).isEqualTo("j•••@empresa-exemplo.test");
    }

    @Test
    void aChangeAuditsBothMaskedValuesAndQueuesTheOldAddressEncrypted() {
        TestCases.Built built = save(TestCases.builder(workspace));
        put(workspace.slug(), built.token(), body(A));

        assertThat(put(workspace.slug(), built.token(), body(B)).statusCode()).isEqualTo(204);

        LinkCase stored = cases.findById(built.linkCase().id()).orElseThrow();
        assertThat(audits(stored)).hasSize(2).last().satisfies(event -> {
            assertThat(event.resumo().anteriorMascarado()).isEqualTo("j•••@empresa-exemplo.test");
            assertThat(event.resumo().novoMascarado()).isEqualTo("m•••@empresa-exemplo.test");
        });
        assertThat(outbox(stored)).singleElement().satisfies(event -> {
            assertThat(event.pendente()).isTrue();
            assertThat(cipher.decrypt(event.payloadCifrado(), event.id())).isEqualTo(A);
        });
        assertThat(mongo.getCollection(OutboxEvent.COLLECTION).find().into(new java.util.ArrayList<>()).toString())
                .doesNotContain(A);
    }

    @Test
    void theSameAddressAgainChangesNothingAndIsNotCounted() {
        TestCases.Built built = save(TestCases.builder(workspace));
        put(workspace.slug(), built.token(), body(A));
        for (int i = 0; i < 6; i++) {
            assertThat(put(workspace.slug(), built.token(), body(A)).statusCode()).isEqualTo(204);
        }

        LinkCase stored = cases.findById(built.linkCase().id()).orElseThrow();
        assertThat(audits(stored)).hasSize(1);
        assertThat(outbox(stored)).isEmpty();
    }

    @Test
    void aCaseOnlyChangeIsANoOpThatKeepsTheStoredAddressAndUsesNoDailyChange() {
        TestCases.Built built = save(TestCases.builder(workspace));
        put(workspace.slug(), built.token(), body(A));
        for (int i = 0; i < 6; i++) {
            assertThat(put(workspace.slug(), built.token(), body("JOAO@Empresa-Exemplo.TEST")).statusCode())
                    .isEqualTo(204);
        }

        LinkCase stored = cases.findById(built.linkCase().id()).orElseThrow();
        assertThat(cipher.decrypt(stored.contato().emailCifrado(), stored.id())).isEqualTo(A);
        assertThat(audits(stored)).hasSize(1);
        assertThat(outbox(stored)).isEmpty();
        for (String address : new String[] {B, C, B, C}) {
            assertThat(put(workspace.slug(), built.token(), body(address)).statusCode()).as("the first address and "
                    + "these four changes are the day's five").isEqualTo(204);
        }
    }

    @Test
    void changingBackToTheFirstAddressInAnotherCaseWithdrawsThePendingNotice() {
        TestCases.Built built = save(TestCases.builder(workspace));
        put(workspace.slug(), built.token(), body(A));
        put(workspace.slug(), built.token(), body(B));

        put(workspace.slug(), built.token(), body(A.toUpperCase(java.util.Locale.ROOT)));

        assertThat(outbox(built.linkCase())).isEmpty();
    }

    @Test
    void changesCoalesceIntoOnePendingEventHoldingTheFirstAddress() {
        TestCases.Built built = save(TestCases.builder(workspace));
        put(workspace.slug(), built.token(), body(A));
        put(workspace.slug(), built.token(), body(B));
        put(workspace.slug(), built.token(), body(C));

        assertThat(outbox(built.linkCase())).singleElement()
                .satisfies(event -> assertThat(cipher.decrypt(event.payloadCifrado(), event.id())).isEqualTo(A));

        put(workspace.slug(), built.token(), body(A));

        assertThat(outbox(built.linkCase())).isEmpty();
    }

    @Test
    void theSixthChangeInADayIs429() {
        TestCases.Built built = save(TestCases.builder(workspace));
        String[] addresses = {A, B, C, A, B};
        for (String address : addresses) {
            assertThat(put(workspace.slug(), built.token(), body(address)).statusCode()).isEqualTo(204);
        }

        Response sixth = put(workspace.slug(), built.token(), body(C));

        assertError(sixth, 429, "LIMITE_TENTATIVAS", null);
        assertThat(sixth.header("Retry-After")).matches("\\d+");
    }

    @Test
    void aDecidedCaseIs409AndAnInvalidAddressOnItIs422() {
        TestCases.Built decided = save(TestCases.builder(workspace).estado(EstadoCaso.approved));

        assertError(put(workspace.slug(), decided.token(), body(A)), 409, "CASO_DECIDIDO", null);
        assertError(put(workspace.slug(), decided.token(), body("a@@x.test")), 422, "EMAIL_INVALIDO", "email");
    }

    @Test
    void invalidMissingAndWronglyTypedAddressesAre422OnEmail() {
        TestCases.Built built = save(TestCases.builder(workspace));

        for (String invalid : List.of(body("a b@x.test"), body("a@x"), body(""), "{}", "{\"email\":null}",
                "{\"email\":42}")) {
            assertError(put(workspace.slug(), built.token(), invalid), 422, "EMAIL_INVALIDO", "email");
        }
        assertThat(cases.findById(built.linkCase().id()).orElseThrow().contato()).isNull();
    }

    @Test
    void unknownAndDisabledLinksAre404BeforeTheCaseLookup() {
        Workspace disabled = workspaces.save(TestWorkspaces.builder().linkAtivo(false).build());
        TestCases.Built onDisabled = save(TestCases.builder(disabled));

        assertError(put("loja-" + java.util.UUID.randomUUID(), TestCases.newToken(), body(A)), 404,
                "LINK_NAO_ENCONTRADO", null);
        assertError(put(disabled.slug(), onDisabled.token(), body(A)), 404, "LINK_NAO_ENCONTRADO", null);
        assertThat(cases.findById(onDisabled.linkCase().id()).orElseThrow().contato()).isNull();
        assertThat(audits(onDisabled.linkCase())).isEmpty();
    }

    @Test
    void unknownAndForeignTokensAreTheSameCaseNotFound() {
        Workspace other = workspaces.save(TestWorkspaces.builder().build());
        TestCases.Built foreign = save(TestCases.builder(other));

        Response unknown = put(workspace.slug(), TestCases.newToken(), body(A));
        Response fromOther = put(workspace.slug(), foreign.token(), body(A));

        assertError(unknown, 404, "CASO_NAO_ENCONTRADO", null);
        assertThat(fromOther.asByteArray()).isEqualTo(unknown.asByteArray());
    }

    @Test
    void contactOutcomesAreCounted() {
        double before = meters.counter("kybr.link.contact", "resultado", "changed").count();
        TestCases.Built built = save(TestCases.builder(workspace));

        put(workspace.slug(), built.token(), body(A));

        assertThat(meters.counter("kybr.link.contact", "resultado", "changed").count()).isEqualTo(before + 1);
    }

    private TestCases.Built save(TestCases.Builder builder) {
        TestCases.Built built = builder.build();
        cases.save(built.linkCase());
        return built;
    }

    private Response put(String slug, String token, String body) {
        return http().header("X-Kybr-Client-IP", clientIp).contentType("application/json").body(body)
                .put("/public/v/{slug}/cases/{token}/contact", slug, token);
    }

    private static String body(String email) {
        return "{\"email\":\"" + email + "\"}";
    }

    private List<AuditEvent> audits(LinkCase linkCase) {
        return mongo.find(Query.query(Criteria.where("case_id").is(linkCase.id())), AuditEvent.class);
    }

    private List<OutboxEvent> outbox(LinkCase linkCase) {
        return mongo.find(Query.query(Criteria.where(OutboxEvent.CASE_ID).is(linkCase.id())), OutboxEvent.class);
    }

    private static void assertError(Response response, int status, String code, String field) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        assertThat(response.jsonPath().getString("code")).isEqualTo(code);
        assertThat(response.jsonPath().getString("field")).isEqualTo(field);
    }
}
