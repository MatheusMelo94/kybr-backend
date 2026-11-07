package com.matheusmelo.kybr.acceptance.link;

import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.APPROVED;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTNER;
import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.acceptance.link.fixtures.Cnpjs;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.TokenSlot;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.company.RuntimeFlagsService;
import com.matheusmelo.kybr.support.TestSecrets;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Provisioning and the kill switches through the operator's admin endpoint on the management port. */
@ExtendWith(OutputCaptureExtension.class)
class AdminAcceptanceIT extends LinkAcceptanceTest {

    private static final String ADMIN = "/actuator/admin";

    @Autowired
    private RuntimeFlagsService flags;

    @AfterEach
    void restoreGlobalSwitches() {
        flags.setAutofill(true);
        flags.setProviderSpend(true);
    }

    private RequestSpecification operator(String token) {
        return RestAssured.given().port(managementPort).header("Authorization", "Bearer " + token)
                .contentType("application/json");
    }

    private RequestSpecification operator() {
        return operator(TestSecrets.ADMIN_TOKEN);
    }

    private Map<String, Object> newWorkspaceInput(String nome, String contatoTipo, String contatoValor) {
        Map<String, Object> input = new HashMap<>();
        input.put("nome", nome);
        input.put("categoria", "varejo");
        input.put("contato_tipo", contatoTipo);
        input.put("contato_valor", contatoValor);
        input.put("plano", "interno");
        return input;
    }

    private Response create(String nome, String contatoTipo, String contatoValor) {
        return operator().body(newWorkspaceInput(nome, contatoTipo, contatoValor)).post(ADMIN + "/workspaces");
    }

    private Response flip(String operation, String slug, String ativo) {
        Map<String, Object> body = new HashMap<>();
        if (slug != null) {
            body.put("slug", slug);
        }
        body.put("ativo", ativo);
        return operator().body(body).post(ADMIN + "/" + operation);
    }

    @Test
    @DisplayName("a missing or wrong token is 401 with no detail, logged and counted; both slots work")
    void theAdminTokenIsRequired() {
        double before = metric("kybr_admin_auth_failed_total");

        Response none = RestAssured.given().port(managementPort).get(ADMIN);
        Response wrong = operator("not-the-admin-token-" + UUID.randomUUID()).get(ADMIN);
        Response current = operator(TestSecrets.ADMIN_TOKEN).get(ADMIN);
        Response previous = operator(TestSecrets.ADMIN_TOKEN_PREVIOUS).get(ADMIN);

        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(none.asString()).isEmpty();
        assertThat(wrong.asString()).isEmpty();
        assertThat(metric("kybr_admin_auth_failed_total")).isEqualTo(before + 2);
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(previous.statusCode()).isEqualTo(200);
        Response onPublicPort = http().header("Authorization", "Bearer " + TestSecrets.ADMIN_TOKEN).get(ADMIN);
        assertError(onPublicPort, 404, httpCode());
    }

    /** The envelope code the public port answers for an unknown path. */
    private String httpCode() {
        return http().get("/qa-unknown-path-" + UUID.randomUUID()).jsonPath().getString("code");
    }

    @Test
    @DisplayName("a created workspace gets an assigned slug, the default policy, and is served at once")
    void aCreatedWorkspaceIsServedAtOnce() {
        Response created = create("Loja Exemplo Provisionada", "email", "vendas@loja-provisionada.test");

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(json(created)).containsOnlyKeys("id", "slug");
        String slug = created.jsonPath().getString("slug");
        assertThat(slug).matches("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$")
                .matches("loja-exemplo-provisionada-[a-z2-7]{6}");
        Workspace stored = workspaces.findBySlug(slug).orElseThrow();
        assertThat(stored.politica()).isEqualTo(TestWorkspaces.defaultPolicy());
        assertThat(stored.documentosHabilitados()).doesNotContain(DocumentoTipo.PROCURACAO)
                .containsExactlyInAnyOrderElementsOf(TestWorkspaces.defaultDocuments());
        assertThat(info(slug).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest(name = "a name [{0}] is refused")
    @ValueSource(strings = {"Kybr", "Loja Kybr Oficial", "Admin", ""})
    void reservedOrEmptyNamesAreRefused(String nome) {
        Response response = create(nome, "email", "vendas@loja.test");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response)).isEqualTo(Map.of("code", "INVALID", "field", "nome"));
    }

    @ParameterizedTest(name = "a contact [{0}] is refused")
    @ValueSource(strings = {"email:a?b@loja.test", "email:a&b@loja.test", "email:a#b@loja.test", "email:a%b@loja.test",
        "email:a b@loja.test", "email:joão@loja.test", "url:http://loja.test", "url:https://u@loja.test",
        "url:javascript:alert(1)"})
    void invalidContactsAreRefused(String contato) {
        String[] parts = contato.split(":", 2);

        Response response = create("Loja Contato Invalido", parts[0], parts[1]);

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID");
    }

    @Test
    @DisplayName("every accepted e-mail contact passes the frontend's strict rule")
    void acceptedEmailsPassTheFrontendRule() {
        for (String email : List.of("a@b.co", "vendas.loja+qa@loja-exemplo.test", "x_y-z@sub.loja.test")) {
            Response created = create("Loja Email Aceito", "email", email);
            if (created.statusCode() == 201) {
                assertThat(email).matches(FrontendContract.STRICT_EMAIL);
                assertThat(info(created.jsonPath().getString("slug")).statusCode()).isEqualTo(200);
            }
        }
    }

    @Test
    @DisplayName("the link switch off closes info, lookup, verify and contact while cases stay readable")
    void theLinkSwitch() {
        String slug = create("Loja Interruptor", "email", "vendas@loja-interruptor.test").jsonPath().getString("slug");
        Workspace workspace = workspaces.findBySlug(slug).orElseThrow();
        String token = start(workspace, Cnpjs.unknownToTheStub(), PARTNER);
        awaitOutcome(workspace, token);

        assertThat(flip("link-ativo", slug, "false").statusCode()).isEqualTo(204);
        assertError(info(slug), 404, "LINK_NAO_ENCONTRADO");
        assertError(lookup(slug, APPROVED, newIp()), 404, "LINK_NAO_ENCONTRADO");
        assertError(verify(slug, APPROVED, PARTNER, newIp()), 404, "LINK_NAO_ENCONTRADO");
        assertError(putEmail(slug, token, "a@b.co", newIp()), 404, "LINK_NAO_ENCONTRADO");
        assertThat(readCase(slug, token, newIp()).statusCode()).isEqualTo(200);

        assertThat(flip("link-ativo", slug, "true").statusCode()).isEqualTo(204);
        assertThat(info(slug).statusCode()).isEqualTo(200);
        assertThat(putEmail(slug, token, "a@b.co", newIp()).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("the autofill switches turn lookups off and leave verification on")
    void theAutofillSwitches() {
        String slug = create("Loja Autofill", "email", "vendas@loja-autofill.test").jsonPath().getString("slug");
        String other = create("Loja Outra Autofill", "email", "vendas@loja-outra.test").jsonPath().getString("slug");

        assertThat(flip("autofill-ativo", slug, "false").statusCode()).isEqualTo(204);
        assertError(lookup(slug, APPROVED, newIp()), 503, "AUTOFILL_DESATIVADO");
        assertThat(lookup(other, APPROVED, newIp()).statusCode()).isEqualTo(200);
        assertThat(verify(slug, Cnpjs.unknownToTheStub(), PARTNER, newIp()).statusCode()).isEqualTo(201);

        assertThat(flip("autofill-global", null, "false").statusCode()).isEqualTo(204);
        assertError(lookup(other, APPROVED, newIp()), 503, "AUTOFILL_DESATIVADO");
        assertThat(verify(other, Cnpjs.unknownToTheStub(), PARTNER, newIp()).statusCode()).isEqualTo(201);
        assertThat(flip("autofill-global", null, "true").statusCode()).isEqualTo(204);
        assertThat(lookup(other, APPROVED, newIp()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a switch takes only the strings true and false")
    void aSwitchTakesOnlyTrueOrFalse() {
        String slug = create("Loja Booleana", "email", "vendas@loja-booleana.test").jsonPath().getString("slug");

        assertThat(flip("link-ativo", slug, "TRUE").statusCode()).isEqualTo(400);
        assertThat(flip("link-ativo", slug, " false").statusCode()).isEqualTo(400);
        Response jsonBoolean = operator().body(Map.of("slug", slug, "ativo", false)).post(ADMIN + "/link-ativo");
        assertThat(jsonBoolean.statusCode()).isEqualTo(422);
        assertThat(workspaces.findBySlug(slug).orElseThrow().linkAtivo()).isTrue();
    }

    @Test
    @DisplayName("every operation is audited as the operator with the token slot; the token is never logged")
    void operationsAreAuditedAndTheTokenStaysOutOfLogs(CapturedOutput output) {
        Response created = create("Loja Auditada", "email", "vendas@loja-auditada.test");
        String slug = created.jsonPath().getString("slug");
        flip("link-ativo", slug, "false");
        operator(TestSecrets.ADMIN_TOKEN_PREVIOUS).body(Map.of("slug", slug, "ativo", "true")).post(ADMIN + "/link-ativo");

        Workspace workspace = workspaces.findBySlug(slug).orElseThrow();
        List<AuditEvent> audits = auditsOf(workspace);
        assertThat(audits).hasSizeGreaterThanOrEqualTo(3);
        assertThat(audits).allSatisfy(a -> assertThat(a.ator()).isEqualTo(Ator.operador));
        assertThat(audits).extracting(AuditEvent::tokenSlot).contains(TokenSlot.CURRENT, TokenSlot.PREVIOUS);
        assertThat(output.getAll()).doesNotContain(TestSecrets.ADMIN_TOKEN).doesNotContain(TestSecrets.ADMIN_TOKEN_PREVIOUS);
        Response read = operator().get(ADMIN + "/" + slug);
        assertThat(json(read).keySet()).doesNotContain("politica", "contato_vendedor", "_id");
    }
}
