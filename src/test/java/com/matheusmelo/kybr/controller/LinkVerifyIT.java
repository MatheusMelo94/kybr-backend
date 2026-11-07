package com.matheusmelo.kybr.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** E03: starting a verification, end to end through the idempotent, transactional completion. */
class LinkVerifyIT extends WebIntegrationTest {

    private static final String CLIENT_IP_HEADER = "X-Kybr-Client-IP";
    private static final String BODY =
            "{\"cnpj\":\"11222333000181\",\"responsavel\":\"Maria Exemplo\",\"ref\":\"pedido-1\",\"ext_id\":\"ext-9\"}";

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private MongoTemplate mongo;

    private Workspace workspace;

    @BeforeEach
    void freshWorkspace() {
        workspace = workspaces.save(TestWorkspaces.builder().build());
    }

    @Test
    void createsACaseAndAnswers201WithOnlyTheToken() {
        Response response = verify(workspace.slug(), UUID.randomUUID().toString(), BODY, "198.51.100.4");

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        Map<String, Object> body = response.jsonPath().getMap("");
        assertThat(body).containsOnlyKeys("token");
        String token = (String) body.get("token");
        assertThat(token).matches("^[A-Za-z0-9_-]{22,64}$");
        assertThat(response.asString()).doesNotContain("pedido-1").doesNotContain("ext-9");
        assertThat(response.header("Location")).isNull();
        assertThat(casesOf(workspace)).singleElement()
                .satisfies(created -> assertThat(created.tokenHash()).isEqualTo(TestCases.hash(token)));
    }

    @Test
    void anExactReplayIsByteIdenticalCreatesNoSecondCaseAndConsumesNoLimit() {
        String key = UUID.randomUUID().toString();
        Response first = verify(workspace.slug(), key, BODY, "198.51.100.4");
        Response replay = verify(workspace.slug(), key, BODY, "198.51.100.5");
        Response again = verify(workspace.slug(), key, BODY, "198.51.100.6");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.asByteArray()).isEqualTo(first.asByteArray());
        assertThat(again.asByteArray()).isEqualTo(first.asByteArray());
        assertThat(casesOf(workspace)).hasSize(1);

        assertThat(verify(workspace.slug(), UUID.randomUUID().toString(), BODY, "198.51.100.7").statusCode())
                .isEqualTo(201);
        assertThat(verify(workspace.slug(), UUID.randomUUID().toString(), BODY, "198.51.100.8").statusCode())
                .isEqualTo(201);
        Response fourth = verify(workspace.slug(), UUID.randomUUID().toString(), BODY, "198.51.100.9");
        assertError(fourth, 429, "LIMITE_CNPJ", null);
        assertThat(fourth.header("Retry-After")).matches("\\d+");
    }

    @Test
    void theSameKeyWithAnotherBodyIs422OnTheKey() {
        String key = UUID.randomUUID().toString();
        verify(workspace.slug(), key, BODY, "198.51.100.4");

        Response mismatch = verify(workspace.slug(), key, BODY.replace("Maria Exemplo", "Ana Exemplo"),
                "198.51.100.4");

        assertError(mismatch, 422, "VALOR_INVALIDO", "Idempotency-Key");
        assertThat(casesOf(workspace)).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "invalid cnpj        | {\"cnpj\":\"11222333000180\",\"responsavel\":\"Maria Exemplo\"}          | CNPJ_INVALIDO        | cnpj",
        "cnpj a number       | {\"cnpj\":11222333000181,\"responsavel\":\"Maria Exemplo\"}            | CNPJ_INVALIDO        | cnpj",
        "missing cnpj        | {\"responsavel\":\"Maria Exemplo\"}                                    | CNPJ_INVALIDO        | cnpj",
        "one-word name       | {\"cnpj\":\"11222333000181\",\"responsavel\":\"Maria\"}                  | RESPONSAVEL_INVALIDO | responsavel",
        "null responsavel    | {\"cnpj\":\"11222333000181\",\"responsavel\":null}                       | RESPONSAVEL_INVALIDO | responsavel",
        "both invalid        | {\"cnpj\":\"1\",\"responsavel\":\"1\"}                                   | CNPJ_INVALIDO        | cnpj",
        "ref non-ascii       | {\"cnpj\":\"11222333000181\",\"responsavel\":\"Maria Exemplo\",\"ref\":\"é\"} | VALOR_INVALIDO | ref",
        "ext_id empty        | {\"cnpj\":\"11222333000181\",\"responsavel\":\"Maria Exemplo\",\"ext_id\":\"\"} | VALOR_INVALIDO | ext_id",
        "unknown property    | {\"cnpj\":\"11222333000181\",\"responsavel\":\"Maria Exemplo\",\"turnstile_token\":\"x\"} | VALOR_INVALIDO | turnstile_token"
    })
    void anInvalidBodyIs422WithTheField(String scenario, String body, String code, String field) {
        Response response = verify(workspace.slug(), UUID.randomUUID().toString(), body, "198.51.100.4");

        assertError(response, 422, code, field);
        assertThat(casesOf(workspace)).isEmpty();
    }

    @Test
    void anUnknownOrDisabledSlugIs404EvenWithAnInvalidBody() {
        Workspace disabled = workspaces.save(TestWorkspaces.builder().linkAtivo(false).build());

        assertError(verify("loja-" + UUID.randomUUID(), UUID.randomUUID().toString(), "{\"cnpj\":\"1\"}",
                "198.51.100.4"), 404, "LINK_NAO_ENCONTRADO", null);
        assertError(verify(disabled.slug(), UUID.randomUUID().toString(), BODY, "198.51.100.4"), 404,
                "LINK_NAO_ENCONTRADO", null);
        assertThat(casesOf(disabled)).isEmpty();
    }

    @Test
    void aMissingIdempotencyKeyIs422OnTheHeader() {
        Response response = http().header(CLIENT_IP_HEADER, "198.51.100.4").contentType("application/json")
                .body(BODY).post("/public/v/{slug}/verify", workspace.slug());

        assertError(response, 422, "VALOR_INVALIDO", "Idempotency-Key");
    }

    private Response verify(String slug, String key, String body, String clientIp) {
        return http().header("Idempotency-Key", key).header(CLIENT_IP_HEADER, clientIp)
                .contentType("application/json").body(body).post("/public/v/{slug}/verify", slug);
    }

    private java.util.List<LinkCase> casesOf(Workspace owner) {
        return mongo.find(Query.query(Criteria.where(LinkCase.WORKSPACE_ID).is(owner.id())), LinkCase.class);
    }

    private static void assertError(Response response, int status, String code, String field) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        assertThat(response.jsonPath().getString("code")).isEqualTo(code);
        assertThat(response.jsonPath().getString("field")).isEqualTo(field);
    }
}
