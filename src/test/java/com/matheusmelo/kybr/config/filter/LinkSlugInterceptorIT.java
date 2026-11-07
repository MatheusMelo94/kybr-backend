package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.support.link.LinkSlugProbeController;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Slug resolution on link routes: after the idempotency check, before argument binding. */
class LinkSlugInterceptorIT extends WebIntegrationTest {

    private static final String VALID_BODY = "{\"name\":\"Loja Exemplo\"}";
    private static final String INVALID_BODY = "{\"name\":\"\"}";

    @Autowired
    private WorkspaceRepository workspaces;

    @Test
    void anActiveWorkspaceReachesTheHandler() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().build());

        Response response = http().get("/public/v/{slug}/slug-probe", workspace.slug());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("slug")).isEqualTo(workspace.slug());
    }

    @Test
    void unknownDisabledUncontactedAndOffPatternSlugsAnswerTheSameNotFound() {
        Workspace disabled = workspaces.save(TestWorkspaces.builder().linkAtivo(false).build());
        Workspace uncontacted = workspaces.save(TestWorkspaces.builder().contato(null).build());

        Response unknown = http().get("/public/v/{slug}/slug-probe", "loja-" + UUID.randomUUID());
        Response offPattern = http().get("/public/v/{slug}/slug-probe", "Loja-Maiuscula");

        assertNotFound(unknown);
        for (String slug : new String[] {disabled.slug(), uncontacted.slug()}) {
            Response response = http().get("/public/v/{slug}/slug-probe", slug);
            assertNotFound(response);
            assertThat(response.asByteArray()).isEqualTo(unknown.asByteArray());
        }
        assertNotFound(offPattern);
        assertThat(offPattern.asByteArray()).isEqualTo(unknown.asByteArray());
    }

    @Test
    void aDisabledLinkStaysReadableOnACaseReadRoute() {
        Workspace disabled = workspaces.save(TestWorkspaces.builder().linkAtivo(false).build());

        Response response = http().get("/public/v/{slug}/slug-probe-readable", disabled.slug());

        assertThat(response.statusCode()).isEqualTo(200);
        assertNotFound(http().get("/public/v/{slug}/slug-probe-readable", "loja-" + UUID.randomUUID()));
    }

    @Test
    void anUnknownSlugWithAnInvalidBodyIsNotFoundNotAValidationError() {
        Response response = verify("loja-" + UUID.randomUUID(), UUID.randomUUID().toString(), INVALID_BODY);

        assertNotFound(response);
    }

    @Test
    void aKnownSlugWithAnInvalidBodyIsAValidationError() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().build());

        Response response = verify(workspace.slug(), UUID.randomUUID().toString(), INVALID_BODY);

        assertThat(response.statusCode()).isEqualTo(422);
    }

    @Test
    void anIdempotentReplayPrecedesTheSlugCheck() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().build());
        String key = UUID.randomUUID().toString();
        Response first = verify(workspace.slug(), key, VALID_BODY);
        workspaces.save(TestWorkspaces.builder().id(workspace.id()).slug(workspace.slug()).linkAtivo(false).build());

        Response replay = verify(workspace.slug(), key, VALID_BODY);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.asByteArray()).isEqualTo(first.asByteArray());
        assertThat(LinkSlugProbeController.calls(workspace.slug())).isEqualTo(1);
    }

    private Response verify(String slug, String key, String body) {
        return http().header("Idempotency-Key", key).contentType("application/json").body(body)
                .post("/public/v/{slug}/slug-probe-verify", slug);
    }

    private static void assertNotFound(Response response) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(404);
        assertThat(response.jsonPath().getString("code")).isEqualTo("LINK_NAO_ENCONTRADO");
        assertThat(response.jsonPath().getString("field")).isNull();
    }
}
