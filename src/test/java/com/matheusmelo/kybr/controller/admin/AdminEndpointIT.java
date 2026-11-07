package com.matheusmelo.kybr.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.management.AdminAuthFailureHandler;
import com.matheusmelo.kybr.config.management.ManagementAccessLogFilter;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.link.TokenSlot;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.RuntimeFlagsRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.company.RuntimeFlagsService;
import com.matheusmelo.kybr.service.provisioning.ProvisioningService;
import com.matheusmelo.kybr.support.MovableClock;
import com.matheusmelo.kybr.support.TestSecrets;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;

class AdminEndpointIT extends WebIntegrationTest {

    private static final String ADMIN = "/actuator/admin";
    private static final String ASSIGNED_SLUG = "^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$";

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private WorkspaceRepository workspaces;
    @Autowired
    private AuditEventRepository audits;
    @Autowired
    private RuntimeFlagsRepository flagsRepository;
    @Autowired
    private RuntimeFlagsService flags;
    @Autowired
    private KybrProperties kybrProperties;

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

    private Map<String, Object> newWorkspace(String nome) {
        return Map.of("nome", nome, "categoria", "varejo", "contato_tipo", "email",
                "contato_valor", "contato@loja.example", "plano", "teste");
    }

    private String create(String nome) {
        Response response = operator().body(newWorkspace(nome)).post(ADMIN + "/workspaces");
        assertThat(response.statusCode()).isEqualTo(201);
        return response.jsonPath().getString("slug");
    }

    private List<AuditEvent> auditsOf(TipoAuditoria tipo) {
        return audits.findAll().stream().filter(event -> event.tipo() == tipo).toList();
    }

    @Test
    void createsAWorkspaceWithAnAssignedSlugAndAnswersOnlyItsIdAndSlug() {
        Response response = operator().body(newWorkspace("Loja Exemplo Norte")).post(ADMIN + "/workspaces");

        assertThat(response.statusCode()).isEqualTo(201);
        Map<String, Object> body = response.jsonPath().getMap("$");
        assertThat(body).containsOnlyKeys("id", "slug");
        String slug = (String) body.get("slug");
        assertThat(slug).matches(ASSIGNED_SLUG).matches("loja-exemplo-norte-[a-z2-7]{6}");
        Workspace stored = workspaces.findBySlug(slug).orElseThrow();
        assertThat(stored.id()).isEqualTo(UUID.fromString((String) body.get("id")));
        assertThat(stored.linkAtivo()).isTrue();
        assertThat(stored.autofillAtivo()).isTrue();
        assertThat(stored.documentosHabilitados()).doesNotContain(DocumentoTipo.PROCURACAO);
    }

    @Test
    void invalidInputIsRefusedByTheService() {
        Map<String, Object> badName = new HashMap<>(newWorkspace("x"));
        badName.put("nome", "Loja <script>");

        Response response = operator().body(badName).post(ADMIN + "/workspaces");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getMap("$")).isEqualTo(Map.of("code", "INVALID", "field", "nome"));
    }

    @Test
    void aNonStringValueIsRefusedBeforeTheServiceAndCreatesNothing() {
        long before = workspaces.count();
        Map<String, Object> numericName = new HashMap<>(newWorkspace("x"));
        numericName.put("nome", 12345);

        Response response = operator().body(numericName).post(ADMIN + "/workspaces");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(workspaces.count()).isEqualTo(before);
    }

    @Test
    void anUnknownOperationWithAValidTokenIsNotFound() {
        assertThat(operator().body(Map.of("ativo", "true")).post(ADMIN + "/apagar-tudo").statusCode())
                .isEqualTo(404);
    }

    @Test
    void anUnknownSlugIsNotFound() {
        Response response = operator().body(Map.of("slug", "nao-existe-abcdef", "ativo", "false"))
                .post(ADMIN + "/link-ativo");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.jsonPath().getString("field")).isEqualTo("slug");
    }

    @ParameterizedTest
    @ValueSource(strings = {"link-ativo", "autofill-ativo", "autofill-global", "gasto-provedor"})
    void aSwitchTakesOnlyTheExactWordsTrueOrFalse(String operation) {
        String slug = create("Loja Booleano " + operation.replace('-', ' '));

        for (String value : List.of("no", "0", "FALSE", " false", "")) {
            Response response = operator().body(Map.of("slug", slug, "ativo", value)).post(ADMIN + "/" + operation);

            assertThat(response.statusCode()).as(value).isEqualTo(400);
            assertThat(response.jsonPath().getString("field")).isEqualTo("ativo");
        }
        assertThat(operator().body(Map.of("slug", slug, "ativo", false)).post(ADMIN + "/" + operation).statusCode())
                .isEqualTo(422);
    }

    @Test
    void theLinkSwitchTakesEffectAtOnceAndIsAuditedWithTheTokenSlot() {
        String slug = create("Loja Link Switch");

        Response off = operator(TestSecrets.ADMIN_TOKEN_PREVIOUS).body(Map.of("slug", slug, "ativo", "false"))
                .post(ADMIN + "/link-ativo");

        assertThat(off.statusCode()).isEqualTo(204);
        Workspace stored = workspaces.findBySlug(slug).orElseThrow();
        assertThat(stored.linkAtivo()).isFalse();
        assertThat(auditsOf(TipoAuditoria.LINK_ATIVO_ALTERADO)).filteredOn(e -> stored.id().equals(e.workspaceId()))
                .singleElement().satisfies(event -> {
                    assertThat(event.ator()).isEqualTo(Ator.operador);
                    assertThat(event.tokenSlot()).isEqualTo(TokenSlot.PREVIOUS);
                });

        operator().body(Map.of("slug", slug, "ativo", "true")).post(ADMIN + "/link-ativo");
        assertThat(workspaces.findBySlug(slug).orElseThrow().linkAtivo()).isTrue();
    }

    @Test
    void thePerSlugAutofillSwitchTakesEffectAtOnce() {
        String slug = create("Loja Autofill Switch");

        Response off = operator().body(Map.of("slug", slug, "ativo", "false")).post(ADMIN + "/autofill-ativo");
        assertThat(off.statusCode()).as(off.asString()).isEqualTo(204);

        assertThat(workspaces.findBySlug(slug).orElseThrow().autofillAtivo()).isFalse();
        assertThat(auditsOf(TipoAuditoria.AUTOFILL_ALTERADO)).anySatisfy(event ->
                assertThat(event.tokenSlot()).isEqualTo(TokenSlot.CURRENT));
    }

    @Test
    void theGlobalSwitchesTakeEffectHereAtOnceAndOnEveryOtherMachineWithinTheFlagCache() {
        MovableClock otherClock = new MovableClock(Instant.parse("2026-11-07T12:00:00Z"));
        RuntimeFlagsService otherMachine = new RuntimeFlagsService(flagsRepository, kybrProperties, otherClock,
                new SimpleMeterRegistry());
        Duration ttl = kybrProperties.runtimeFlags().cacheTtl();
        assertThat(otherMachine.autofillOn()).isTrue();
        assertThat(otherMachine.providerSpendOn()).isTrue();

        assertThat(operator().body(Map.of("ativo", "false")).post(ADMIN + "/autofill-global").statusCode())
                .isEqualTo(204);
        assertThat(operator().body(Map.of("ativo", "false")).post(ADMIN + "/gasto-provedor").statusCode())
                .isEqualTo(204);

        assertThat(flags.autofillOn()).isFalse();
        assertThat(flags.providerSpendOn()).isFalse();
        otherClock.advance(ttl.minusMillis(1));
        assertThat(otherMachine.autofillOn()).isTrue();
        otherClock.advance(Duration.ofMillis(1));
        assertThat(otherMachine.autofillOn()).isFalse();
        assertThat(otherMachine.providerSpendOn()).isFalse();
        assertThat(ttl).isLessThanOrEqualTo(Duration.ofSeconds(10));
        assertThat(auditsOf(TipoAuditoria.AUTOFILL_GLOBAL_ALTERADO)).isNotEmpty();
        assertThat(auditsOf(TipoAuditoria.GASTO_PROVEDOR_ALTERADO)).isNotEmpty();
    }

    @Test
    void readsOneWorkspaceAndTheGlobalSwitchesWithoutInternalIds() {
        String slug = create("Loja Leitura");

        Response workspace = operator().get(ADMIN + "/" + slug);
        Response global = operator().get(ADMIN);

        assertThat(workspace.statusCode()).isEqualTo(200);
        assertThat(workspace.jsonPath().getMap("$")).containsOnlyKeys("id", "slug", "nome", "categoria", "plano",
                "link_ativo", "autofill_ativo");
        assertThat(workspace.jsonPath().getString("slug")).isEqualTo(slug);
        assertThat(global.statusCode()).isEqualTo(200);
        assertThat(global.jsonPath().getMap("$")).isEqualTo(Map.of("autofill_global", true, "gasto_provedor", true));
        assertThat(operator().get(ADMIN + "/nao-existe-abcdef").statusCode()).isEqualTo(404);
    }

    @Test
    void theTokenNeverAppearsInTheLogs() {
        List<ILoggingEvent> events = new ArrayList<>();
        try (CapturedEvents access = CapturedEvents.of(ManagementAccessLogFilter.class);
                CapturedEvents failures = CapturedEvents.of(AdminAuthFailureHandler.class);
                CapturedEvents service = CapturedEvents.of(ProvisioningService.class);
                CapturedEvents endpoint = CapturedEvents.of(AdminEndpoint.class)) {
            create("Loja Sem Token No Log");
            operator("errado").get(ADMIN);
            operator(TestSecrets.ADMIN_TOKEN_PREVIOUS).get(ADMIN);
            events.addAll(access.events());
            events.addAll(failures.events());
            events.addAll(service.events());
            events.addAll(endpoint.events());
        }

        assertThat(events).isNotEmpty().allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(TestSecrets.ADMIN_TOKEN, TestSecrets.ADMIN_TOKEN_PREVIOUS));
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN, ADMIN + "/workspaces", ADMIN + "/link-ativo"})
    void thePublicPortNeverServesTheAdminEndpoint(String path) {
        Response get = http().header("Authorization", "Bearer " + TestSecrets.ADMIN_TOKEN).get(path);
        Response post = http().header("Authorization", "Bearer " + TestSecrets.ADMIN_TOKEN)
                .contentType("application/json").body(Map.of("ativo", "false")).post(path);

        for (Response response : List.of(get, post)) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.jsonPath().getString("code")).isEqualTo("RECURSO_NAO_ENCONTRADO");
        }
        assertThat(flags.autofillOn()).isTrue();
    }
}
