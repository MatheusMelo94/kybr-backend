package com.matheusmelo.kybr.service.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.link.TokenSlot;
import com.matheusmelo.kybr.model.workspace.ContatoVendedor;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Plano;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.company.CompanyLookupService;
import com.matheusmelo.kybr.service.company.ProviderBudgets;
import com.matheusmelo.kybr.service.company.ProviderBudgets.Decision;
import com.matheusmelo.kybr.service.company.RuntimeFlagsService;
import com.matheusmelo.kybr.service.provisioning.ProvisioningException.Reason;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoTransactionManager;

class ProvisioningServiceIT extends MongoIntegrationTest {

    private static final String SLUG_PATTERN = "^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$";

    @Autowired
    private ProvisioningService provisioning;
    @Autowired
    private WorkspaceRepository workspaces;
    @Autowired
    private AuditEventRepository audits;
    @Autowired
    private RuntimeFlagsService flags;
    @Autowired
    private CompanyLookupService lookups;
    @Autowired
    private ProviderBudgets budgets;
    @Autowired
    private MongoTransactionManager transactionManager;
    @Autowired
    private Clock clock;

    @AfterEach
    void restoreGlobalSwitches() {
        flags.setAutofill(true);
        flags.setProviderSpend(true);
    }

    @Test
    void createAssignsASlugTheDefaultPolicyAndDocumentsAndAudits() {
        ProvisionedWorkspace created = provisioning.create(input("Loja Exemplo Ação", "email", "contato@loja.test"),
                TokenSlot.CURRENT);

        assertThat(created.slug()).matches(SLUG_PATTERN).matches("loja-exemplo-acao-[a-z2-7]{6}");
        Workspace stored = workspaces.findById(created.id()).orElseThrow();
        assertThat(stored.slug()).isEqualTo(created.slug());
        assertThat(stored.nome()).isEqualTo("Loja Exemplo Ação");
        assertThat(stored.contatoVendedor()).isEqualTo(new ContatoVendedor(ContatoVendedor.Tipo.email,
                "contato@loja.test"));
        assertThat(stored.plano()).isEqualTo(new Plano(Plano.Tipo.interno));
        assertThat(stored.linkAtivo()).isTrue();
        assertThat(stored.autofillAtivo()).isTrue();
        assertThat(stored.politica()).isEqualTo(TestWorkspaces.defaultPolicy());
        assertThat(stored.documentosHabilitados()).doesNotContain(DocumentoTipo.PROCURACAO)
                .hasSize(DocumentoTipo.values().length - 1);
        assertThat(audit(TipoAuditoria.WORKSPACE_CRIADO, created.id())).satisfies(event -> {
            assertThat(event.ator()).isEqualTo(Ator.operador);
            assertThat(event.tokenSlot()).isEqualTo(TokenSlot.CURRENT);
            assertThat(event.resumo().slug()).isEqualTo(created.slug());
            assertThat(event.resumo().slugExplicito()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Admin", "Loja Kybr Oficial", "api", "Suporte Kybr"})
    void aReservedNameOrANameContainingOneIsRejected(String nome) {
        assertRejected(input(nome, "email", "contato@loja.test"), "nome");
    }

    @ParameterizedTest
    @ValueSource(strings = {"contato?x@loja.test", "a&b@loja.test", "a#b@loja.test", "a%b@loja.test",
        "a b@loja.test", "josé@loja.test", "contato@lojá.test", "contato@localhost", "@loja.test"})
    void anInvalidContactEmailIsRejected(String email) {
        assertRejected(input("Loja Valida", "email", email), "contato_vendedor");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://loja.test/contato", "https://u@loja.test/x", "javascript:alert(1)", "https://",
        "loja.test/contato"})
    void anInvalidContactUrlIsRejected(String url) {
        assertRejected(input("Loja Valida", "url", url), "contato_vendedor");
    }

    @Test
    void aValidContactUrlIsAccepted() {
        ProvisionedWorkspace created = provisioning.create(input("Loja Url", "url", "https://loja.test/fale-conosco"),
                TokenSlot.PREVIOUS);

        assertThat(workspaces.findById(created.id()).orElseThrow().contatoVendedor().valor())
                .isEqualTo("https://loja.test/fale-conosco");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "HTTPS://Loja.TEST:443/a/./b/../c, https://loja.test/a/c",
        "https://loja.test, https://loja.test/",
        "https://LOJA.test/fale-conosco/?canal=link#topo, https://loja.test/fale-conosco/?canal=link",
        "https://loja.test:8443/x, https://loja.test:8443/x",
        "https://loja.test//outra.test/x, https://loja.test/outra.test/x",
        "https://loja.test///outra.test:8443/x?y=1, https://loja.test/outra.test:8443/x?y=1"})
    void aContactUrlIsStoredInCanonicalForm(String typed, String stored) {
        ProvisionedWorkspace created = provisioning.create(input("Loja Canonica", "url", typed), TokenSlot.CURRENT);

        assertThat(workspaces.findById(created.id()).orElseThrow().contatoVendedor().valor()).isEqualTo(stored);
    }

    @Test
    void inputIsRevalidatedAsATypedCommand() {
        Map<String, Object> unknownField = input("Loja Valida", "email", "contato@loja.test");
        unknownField.put("link_ativo", false);
        assertRejected(unknownField, "link_ativo");

        Map<String, Object> numericName = input("Loja Valida", "email", "contato@loja.test");
        numericName.put("nome", 12345);
        assertRejected(numericName, "nome");

        assertRejected(input("Loja Valida", "email", "a".repeat(250) + "@loja.test"), "contato_vendedor");
        assertRejected(input("x".repeat(81), "email", "contato@loja.test"), "nome");
        assertRejected(input("Loja Valida", "telefone", "11999999999"), "contato_tipo");

        Map<String, Object> badPlan = input("Loja Valida", "email", "contato@loja.test");
        badPlan.put("plano", "gratis");
        assertRejected(badPlan, "plano");
    }

    @Test
    void anExplicitSlugIsAcceptedWhenAllowedAndAudited() {
        String slug = "loja-exemplo-" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> command = input("Loja Exemplo", "email", "contato@loja.test");
        command.put("slug", slug);

        ProvisionedWorkspace created = provisioning.create(command, TokenSlot.CURRENT);

        assertThat(created.slug()).isEqualTo(slug);
        assertThat(audit(TipoAuditoria.WORKSPACE_CRIADO, created.id()).resumo().slugExplicito()).isTrue();
        assertThatThrownBy(() -> provisioning.create(command, TokenSlot.CURRENT))
                .isInstanceOfSatisfying(ProvisioningException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CONFLICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Loja-Exemplo", "loja_exemplo", "-loja", "admin", "kybr-oficial"})
    void anExplicitSlugMustBeWellFormedAndNotReserved(String slug) {
        Map<String, Object> command = input("Loja Exemplo", "email", "contato@loja.test");
        command.put("slug", slug);
        assertRejected(command, "slug");
    }

    @Test
    void anExplicitSlugIsRejectedWhenNotAllowed() {
        ProvisioningService strict = new ProvisioningService(workspaces, audits, flags, new SlugAssigner(),
                transactionManager, false, clock);
        Map<String, Object> command = input("Loja Exemplo", "email", "contato@loja.test");
        command.put("slug", "loja-exemplo-x");

        assertThatThrownBy(() -> strict.create(command, TokenSlot.CURRENT))
                .isInstanceOfSatisfying(ProvisioningException.class, e -> assertThat(e.field()).isEqualTo("slug"));
    }

    @Test
    void aSlugCollisionRetriesWithANewSuffix() {
        Deque<String> suffixes = new ArrayDeque<>(List.of("aaaaaa", "aaaaaa", "bbbbbb"));
        String nome = "Colisao " + UUID.randomUUID().toString().substring(0, 4).replaceAll("[0-9]", "x");
        ProvisioningService service = new ProvisioningService(workspaces, audits, flags,
                new SlugAssigner(suffixes::pop), transactionManager, true, clock);

        ProvisionedWorkspace first = service.create(input(nome, "email", "contato@loja.test"), TokenSlot.CURRENT);
        ProvisionedWorkspace second = service.create(input(nome, "email", "contato@loja.test"), TokenSlot.CURRENT);

        assertThat(first.slug()).endsWith("-aaaaaa");
        assertThat(second.slug()).endsWith("-bbbbbb");
    }

    @Test
    void theLinkSwitchTakesEffectAndIsAudited() {
        ProvisionedWorkspace created = provisioning.create(input("Loja Switch", "email", "contato@loja.test"),
                TokenSlot.CURRENT);

        provisioning.setLinkAtivo(created.slug(), false, TokenSlot.PREVIOUS);

        assertThat(workspaces.findById(created.id()).orElseThrow().linkAtivo()).isFalse();
        AuditEvent event = audit(TipoAuditoria.LINK_ATIVO_ALTERADO, created.id());
        assertThat(event.ator()).isEqualTo(Ator.operador);
        assertThat(event.tokenSlot()).isEqualTo(TokenSlot.PREVIOUS);
        assertThat(event.resumo().ativo()).isFalse();
    }

    @Test
    void theWorkspaceAutofillSwitchTurnsLookupsOff() {
        ProvisionedWorkspace created = provisioning.create(input("Loja Autofill", "email", "contato@loja.test"),
                TokenSlot.CURRENT);

        provisioning.setAutofillAtivo(created.slug(), false, TokenSlot.CURRENT);

        assertThat(lookups.autofillEnabled(workspaces.findById(created.id()).orElseThrow())).isFalse();
        assertThat(audit(TipoAuditoria.AUTOFILL_ALTERADO, created.id()).resumo().ativo()).isFalse();
    }

    @Test
    void theGlobalSwitchesTurnLookupsOffAndDeferVerifications() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder()
                .slug("loja-" + UUID.randomUUID().toString().substring(0, 8)).build());

        provisioning.setGlobalAutofill(false, TokenSlot.CURRENT);
        provisioning.setGastoProvedor(false, TokenSlot.CURRENT);

        assertThat(lookups.autofillEnabled(workspace)).isFalse();
        assertThat(budgets.canSpendD3(workspace.slug())).isEqualTo(Decision.SPEND_OFF);
        assertThat(globalAudit(TipoAuditoria.AUTOFILL_GLOBAL_ALTERADO).tokenSlot()).isEqualTo(TokenSlot.CURRENT);
        assertThat(globalAudit(TipoAuditoria.GASTO_PROVEDOR_ALTERADO).resumo().ativo()).isFalse();
    }

    @Test
    void aSwitchOnAnUnknownSlugIsNotFound() {
        assertThatThrownBy(() -> provisioning.setLinkAtivo("nao-existe-" + UUID.randomUUID(), false, TokenSlot.CURRENT))
                .isInstanceOfSatisfying(ProvisioningException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND));
    }

    private void assertRejected(Map<String, Object> command, String field) {
        long before = workspaces.count();
        assertThatThrownBy(() -> provisioning.create(command, TokenSlot.CURRENT))
                .isInstanceOfSatisfying(ProvisioningException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.INVALID);
                    assertThat(e.field()).isEqualTo(field);
                });
        assertThat(workspaces.count()).isEqualTo(before);
    }

    private static Map<String, Object> input(String nome, String tipo, String valor) {
        Map<String, Object> input = new HashMap<>();
        input.put("nome", nome);
        input.put("categoria", "geral");
        input.put("contato_tipo", tipo);
        input.put("contato_valor", valor);
        input.put("plano", "interno");
        return input;
    }

    private AuditEvent audit(TipoAuditoria tipo, UUID workspaceId) {
        return audits.findAll().stream().filter(e -> e.tipo() == tipo && workspaceId.equals(e.workspaceId()))
                .findFirst().orElseThrow();
    }

    private AuditEvent globalAudit(TipoAuditoria tipo) {
        return audits.findAll().stream().filter(e -> e.tipo() == tipo)
                .max((a, b) -> a.ocorridoEm().compareTo(b.ocorridoEm())).orElseThrow();
    }
}
