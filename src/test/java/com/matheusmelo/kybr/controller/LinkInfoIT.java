package com.matheusmelo.kybr.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.matheusmelo.kybr.model.workspace.ContatoVendedor;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.link.LinkService;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/** E01: the link's public info. */
class LinkInfoIT extends WebIntegrationTest {

    @Autowired
    private WorkspaceRepository workspaces;

    @Test
    void anEmailContactAnswersExactlyNameAndContact() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().nome("Loja Exemplo").build());

        Response response = http().get("/public/v/{slug}", workspace.slug());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.contentType()).startsWith("application/json");
        Map<String, Object> body = response.jsonPath().getMap("");
        assertThat(body).containsOnlyKeys("nome", "contato_vendedor");
        assertThat(body.get("nome")).isEqualTo("Loja Exemplo");
        assertThat(body.get("contato_vendedor")).isEqualTo(Map.of("tipo", "email", "valor", "contato@example.com"));
    }

    @Test
    void aUrlContactAnswersTheUrl() {
        Workspace workspace = workspaces.save(
                TestWorkspaces.builder().contatoUrl("https://loja.example.com/contato").build());

        Response response = http().get("/public/v/{slug}", workspace.slug());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getMap("contato_vendedor"))
                .isEqualTo(Map.of("tipo", "url", "valor", "https://loja.example.com/contato"));
    }

    @Test
    void theLinkNotFoundCasesShareOneBody() {
        Workspace disabled = workspaces.save(TestWorkspaces.builder().linkAtivo(false).build());
        Workspace uncontacted = workspaces.save(TestWorkspaces.builder().contato(null).build());
        Response unknown = http().get("/public/v/{slug}", "loja-" + UUID.randomUUID());

        assertLinkNotFound(unknown);
        for (String slug : List.of(disabled.slug(), uncontacted.slug(), "Loja", "-loja", "loja-", "lo..ja",
                "a".repeat(65))) {
            Response response = http().get("/public/v/{slug}", slug);
            assertLinkNotFound(response);
            assertThat(response.asByteArray()).as(slug).isEqualTo(unknown.asByteArray());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "url, javascript:alert(1)",
        "url, http://x.test",
        "url, https://u:p@x.test",
        "email, a?b@x.test",
        "email, a b@x.test",
        "email, a%40@x.test",
        "email, a#b@x.test"
    })
    void aStoredContactThatBreaksThePublishingRulesIsNotFoundAndLoggedOnceByReason(String tipo, String valor) {
        Workspace workspace = workspaces.save(TestWorkspaces.builder()
                .contato(new ContatoVendedor(ContatoVendedor.Tipo.valueOf(tipo), valor)).build());

        Response response;
        List<ILoggingEvent> events;
        try (CapturedEvents captured = CapturedEvents.of(LinkService.class)) {
            response = http().get("/public/v/{slug}", workspace.slug());
            events = captured.events();
        }

        assertLinkNotFound(response);
        assertThat(events).hasSize(1);
        String line = events.getFirst().getFormattedMessage();
        assertThat(line).contains("event=link_contact_invalid").contains("reason=").doesNotContain(valor);
    }

    @Test
    void aContactChangeIsVisibleOnTheNextCall() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().build());
        http().get("/public/v/{slug}", workspace.slug());
        workspaces.save(TestWorkspaces.builder().id(workspace.id()).slug(workspace.slug())
                .contatoUrl("https://loja.example.com/novo").build());

        Response response = http().get("/public/v/{slug}", workspace.slug());

        assertThat(response.jsonPath().getString("contato_vendedor.valor")).isEqualTo("https://loja.example.com/novo");
    }

    @Test
    void theBffKeyWithoutAClientIpIsEnough() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().build());

        assertThat(http().get("/public/v/{slug}", workspace.slug()).statusCode()).isEqualTo(200);
        assertThat(httpWithoutKey().get("/public/v/{slug}", workspace.slug()).statusCode()).isEqualTo(403);
    }

    private static void assertLinkNotFound(Response response) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(404);
        assertThat(response.jsonPath().getString("code")).isEqualTo("LINK_NAO_ENCONTRADO");
    }
}
