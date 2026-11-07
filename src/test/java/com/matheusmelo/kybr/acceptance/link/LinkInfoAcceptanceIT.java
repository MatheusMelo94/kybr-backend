package com.matheusmelo.kybr.acceptance.link;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.matheusmelo.kybr.model.workspace.ContatoVendedor;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.link.LinkService;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Link info (E01), the BFF key on every link route, and the wrong-method envelope. */
class LinkInfoAcceptanceIT extends LinkAcceptanceTest {

    @Test
    @DisplayName("an e-mail contact is served as exactly the name and the contact")
    void anEmailContactIsServed() {
        Workspace workspace = newWorkspace(TestWorkspaces.builder().nome("Loja Exemplo Centro")
                .contato(new ContatoVendedor(ContatoVendedor.Tipo.email, "vendas.centro@loja-exemplo.test")));

        Response response = info(workspace.slug());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response)).isEqualTo(Map.of("nome", "Loja Exemplo Centro",
                "contato_vendedor", Map.of("tipo", "email", "valor", "vendas.centro@loja-exemplo.test")));
    }

    @Test
    @DisplayName("a URL contact is served as an absolute https URL without credentials")
    void aUrlContactIsServed() {
        Workspace workspace = newWorkspace(TestWorkspaces.builder().contatoUrl("https://loja-exemplo.test/contato"));

        Response response = info(workspace.slug());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getMap("contato_vendedor"))
                .isEqualTo(Map.of("tipo", "url", "valor", "https://loja-exemplo.test/contato"));
    }

    @Test
    @DisplayName("unknown, disabled, uncontacted and off-pattern slugs are one identical 404")
    void everyUnservableSlugIsTheSameNotFound() {
        Workspace disabled = newWorkspace(TestWorkspaces.builder().linkAtivo(false));
        Workspace uncontacted = newWorkspace(TestWorkspaces.builder().contato(null));
        List<String> slugs = List.of("loja-desconhecida-qa", disabled.slug(), uncontacted.slug(), "Loja", "-loja",
                "loja-", "a".repeat(65), "lo..ja", "loja%2Fx");

        List<Response> responses = new ArrayList<>();
        for (String slug : slugs) {
            responses.add(http().urlEncodingEnabled(false).get("/public/v/" + slug));
        }

        Response first = responses.getFirst();
        assertError(first, 404, "LINK_NAO_ENCONTRADO");
        for (int i = 0; i < responses.size(); i++) {
            Response response = responses.get(i);
            if (response.statusCode() == 400) {
                // An encoded slash or dot segment is refused by the path firewall before routing.
                assertError(response, 400, "REQUISICAO_INVALIDA");
                continue;
            }
            assertThat(response.asByteArray()).as(slugs.get(i)).isEqualTo(first.asByteArray());
        }
    }

    @ParameterizedTest(name = "stored contact {0} is never served")
    @ValueSource(strings = {"javascript:alert(1)", "http://x.test", "https://u:p@x.test", "a?b@x.test", "a b@x.test",
        "a%40@x.test", "a#b@x.test"})
    void anInvalidStoredContactIsNotFoundAndLoggedByReasonOnly(String value) {
        ContatoVendedor.Tipo tipo = value.contains("://") || value.startsWith("javascript")
                ? ContatoVendedor.Tipo.url : ContatoVendedor.Tipo.email;
        Workspace workspace = newWorkspace(TestWorkspaces.builder().contato(new ContatoVendedor(tipo, value)));

        try (CapturedEvents events = CapturedEvents.of(LinkService.class)) {
            Response response = info(workspace.slug());

            assertError(response, 404, "LINK_NAO_ENCONTRADO");
            List<ILoggingEvent> invalid = events.events().stream()
                    .filter(e -> e.getFormattedMessage().contains("event=link_contact_invalid")).toList();
            assertThat(invalid).hasSize(1);
            assertThat(invalid.getFirst().getFormattedMessage()).contains("reason=").doesNotContain(value);
        }
    }

    @Test
    @DisplayName("a contact change is visible on the next read")
    void aContactChangeIsVisibleAtOnce() {
        Workspace workspace = newWorkspace();
        assertThat(info(workspace.slug()).jsonPath().getString("contato_vendedor.valor")).isEqualTo("contato@example.com");

        workspaces.save(new Workspace(workspace.id(), workspace.nome(), workspace.slug(), workspace.categoria(),
                new ContatoVendedor(ContatoVendedor.Tipo.email, "novo.contato@loja-exemplo.test"), workspace.plano(),
                workspace.linkAtivo(), workspace.autofillAtivo(), workspace.politica(),
                workspace.documentosHabilitados(), workspace.criadoEm()));

        assertThat(info(workspace.slug()).jsonPath().getString("contato_vendedor.valor"))
                .isEqualTo("novo.contato@loja-exemplo.test");
    }

    @Test
    @DisplayName("the info read needs no client IP, and its absence is counted as expected")
    void theInfoReadNeedsNoClientIp() {
        Workspace workspace = newWorkspace();
        double expectedBefore = metric("kybr_client_ip_absent_total", "esperado=\"true\"");
        double notExpectedBefore = metric("kybr_client_ip_absent_total", "esperado=\"false\"");

        Response response = info(workspace.slug());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(metric("kybr_client_ip_absent_total", "esperado=\"false\"")).isEqualTo(notExpectedBefore + 1);
        assertThat(metric("kybr_client_ip_absent_total", "esperado=\"true\"")).isEqualTo(expectedBefore);
    }

    @Test
    @DisplayName("every link route refuses a request without the BFF key")
    void everyLinkRouteNeedsTheBffKey() {
        Workspace workspace = newWorkspace();
        String token = TestCases.newToken();
        String base = "/public/v/" + workspace.slug();

        List<Response> responses = List.of(
                httpWithoutKey().get(base),
                httpWithoutKey().get(base + "/lookup?cnpj=12345678000195"),
                httpWithoutKey().header(IDEMPOTENCY_KEY, newKey()).contentType(ContentType.JSON)
                        .body(body("12345678000195", "Maria Exemplo")).post(base + "/verify"),
                httpWithoutKey().get(base + "/cases/" + token),
                httpWithoutKey().contentType(ContentType.JSON).body(Map.of("email", "a@b.co"))
                        .put(base + "/cases/" + token + "/contact"),
                httpWithoutKey().header(BFF_KEY_HEADER, "wrong-key").get(base));

        for (Response response : responses) {
            assertError(response, 403, "BFF_KEY_AUSENTE");
        }
        assertThat(responses.get(5).asByteArray()).isEqualTo(responses.get(0).asByteArray());
    }

    @Test
    @DisplayName("a wrong method is a 405 envelope with Allow, and HEAD has no body")
    void wrongMethodsAre405WithAllow() {
        Workspace workspace = newWorkspace();
        String base = "/public/v/" + workspace.slug();
        String token = TestCases.newToken();

        List<Response> wrong = List.of(
                from(newIp()).contentType(ContentType.JSON).body("{}").post(base),
                from(newIp()).get(base + "/verify"),
                from(newIp()).contentType(ContentType.JSON).body("{}").post(base + "/cases/" + token),
                from(newIp()).get(base + "/cases/" + token + "/contact"));

        for (Response response : wrong) {
            assertError(response, 405, "METODO_NAO_PERMITIDO");
            assertThat(response.header("Allow")).isNotBlank();
        }
        Response headInfo = from(newIp()).head(base);
        Response headCase = from(newIp()).head(base + "/cases/" + token);
        assertThat(headInfo.statusCode()).isLessThan(500);
        assertThat(headInfo.asByteArray()).isEmpty();
        assertThat(headCase.statusCode()).isLessThan(500);
        assertThat(headCase.asByteArray()).isEmpty();
    }
}
