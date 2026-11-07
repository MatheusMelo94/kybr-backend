package com.matheusmelo.kybr.acceptance.link;

import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.APPROVED;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.MEI_OWNER;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.MEI_UNMASKED;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTIAL_MATCH;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTIAL_PARTNER;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTNER;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.STUB_SENTINEL;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.UNMASKED_CPF;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.UNRELATED_PARTNER;
import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.acceptance.link.FrontendContract.Route;
import com.matheusmelo.kybr.acceptance.link.fixtures.Cnpjs;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.support.TestSecrets;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Rules across every link route: no conditional responses, no PII in logs or storage, the contract file. */
@ExtendWith(OutputCaptureExtension.class)
class CrossCuttingAcceptanceIT extends LinkAcceptanceTest {

    @Test
    @DisplayName("conditional request headers never produce ETag, Last-Modified or 304")
    void noConditionalResponses() {
        Workspace workspace = newWorkspace();
        String token = start(workspace, Cnpjs.unknownToTheStub(), PARTNER);
        String base = "/public/v/" + workspace.slug();
        Supplier<RequestSpecification> conditional = () -> from(newIp()).header("If-None-Match", "*")
                .header("If-Modified-Since", "Sat, 07 Nov 2026 12:00:00 GMT");

        List<Response> responses = List.of(
                conditional.get().get(base),
                conditional.get().queryParam("cnpj", APPROVED).get(base + "/lookup"),
                conditional.get().header(IDEMPOTENCY_KEY, newKey()).contentType(ContentType.JSON)
                        .body(body(Cnpjs.unknownToTheStub(), PARTNER)).post(base + "/verify"),
                conditional.get().get(base + "/cases/" + token),
                conditional.get().contentType(ContentType.JSON).body(Map.of("email", "a@b.co"))
                        .put(base + "/cases/" + token + "/contact"));

        assertThat(responses).extracting(Response::statusCode).containsExactly(200, 200, 201, 200, 204);
        for (Response response : responses) {
            assertThat(response.header("ETag")).isNull();
            assertThat(response.header("Last-Modified")).isNull();
        }
    }

    @Test
    @DisplayName("the frontend contract check refuses bodies the frontend would reject, coerce or drop")
    void theFrontendContractCheckBites() {
        String good = "{\"estado\":\"approved\",\"criado_em\":\"2026-11-07T14:03:11Z\",\"resultado\":{\"razao_social\":"
                + "\"Loja\",\"responsavel\":\"Maria Exemplo\",\"consultado_em\":\"2026-11-07T14:03:11Z\","
                + "\"confirmacoes\":[{\"codigo\":\"SITUACAO_ATIVA\"},{\"codigo\":\"IE_ATIVA\",\"uf\":\"SP\"}]}}";
        assertThat(FrontendContract.check(Route.CASE, 200, good)).isEmpty();

        List<String> bad = List.of(
                good.replace("\"consultado_em\":\"2026-11-07T14:03:11Z\"", "\"consultado_em\":\"2026-11-07\""),
                good.replace(",\"uf\":\"SP\"", ""),
                good.replace("SITUACAO_ATIVA", "NOVO_CODIGO"),
                good.replace("\"razao_social\":\"Loja\",", ""),
                "{\"estado\":\"pending\",\"criado_em\":\"2026-11-07T14:03:11Z\",\"resultado\":{\"razao_social\":\"L\","
                        + "\"responsavel\":\"M E\",\"consultado_em\":\"2026-11-07T14:03:11Z\",\"itens\":[]}}",
                "{\"estado\":\"blocked\",\"criado_em\":\"2026-11-07T14:03:11Z\",\"resultado\":{\"responsavel\":\"M E\","
                        + "\"variante\":\"DADOS_NAO_ENCONTRADOS\",\"motivo\":{\"codigo\":\"SITUACAO_IRREGULAR\","
                        + "\"situacao\":\"inapta\"}}}",
                "{\"estado\":\"pending\",\"criado_em\":\"2026-11-07T14:03:11Z\",\"resultado\":{\"razao_social\":\"L\","
                        + "\"responsavel\":\"M E\",\"consultado_em\":\"2026-11-07T14:03:11Z\",\"prazo_revisao\":"
                        + "\"2026-11-09T14:03:11Z\",\"itens\":[{\"codigo\":\"EMPRESA_RECENTE\",\"dias\":0}]}}");
        for (String body : bad) {
            assertThat(FrontendContract.check(Route.CASE, 200, body)).as(body).isNotEmpty();
        }
        assertThat(FrontendContract.check(Route.LOOKUP, 200, "{\"razao_social\":\"JOAO 12345678909\",\"municipio\":"
                + "\"X\",\"uf\":\"SP\",\"cnae\":{\"descricao\":\"Y\"}}")).isNotEmpty();
        assertThat(FrontendContract.check(Route.INFO, 200,
                "{\"nome\":\"L\",\"contato_vendedor\":{\"tipo\":\"email\",\"valor\":\"a?b@x.test\"}}")).isNotEmpty();
    }

    @Test
    @DisplayName("no canary value reaches the logs, and storage holds no raw token, e-mail, partner or CPF")
    void noPiiInLogsOrStorage(CapturedOutput output) {
        Workspace workspace = newWorkspace();
        String responsavelCanary = "Canario Responsavel Qa";
        String refCanary = "qa-ref-canary-" + UUID.randomUUID();
        String extCanary = "qa-ext-canary-" + UUID.randomUUID();
        String emailCanary = "canario.sweep." + UUID.randomUUID().toString().substring(0, 8) + "@empresa-exemplo.test";
        List<String> tokens = new ArrayList<>();

        lookup(workspace.slug(), MEI_UNMASKED, newIp());
        Map<String, Object> body = body(PARTIAL_MATCH, responsavelCanary);
        body.put("ref", refCanary);
        body.put("ext_id", extCanary);
        freeProviderGate();
        String partialToken = verifyBody(workspace.slug(), body, newIp(), newKey()).jsonPath().getString("token");
        tokens.add(partialToken);
        awaitOutcome(workspace, partialToken);
        tokens.add(start(workspace, MEI_UNMASKED, MEI_OWNER));
        awaitOutcome(workspace, tokens.getLast());
        putEmail(workspace.slug(), partialToken, emailCanary, newIp());
        putEmail(workspace.slug(), partialToken, "segundo." + emailCanary, newIp());
        readCase(workspace.slug(), partialToken, newIp());
        RestAssured.given().port(managementPort).header("Authorization", "Bearer " + TestSecrets.ADMIN_TOKEN)
                .get("/actuator/admin");

        String logs = output.getAll();
        assertThat(logs).contains("event=access");
        List<String> canaries = new ArrayList<>(List.of(MEI_UNMASKED, UNMASKED_CPF, STUB_SENTINEL, PARTIAL_PARTNER,
                responsavelCanary, refCanary, extCanary, emailCanary, TestSecrets.ADMIN_TOKEN));
        canaries.addAll(tokens);
        for (String canary : canaries) {
            assertThat(logs).as("logs").doesNotContain(canary)
                    .doesNotContain(URLEncoder.encode(canary, StandardCharsets.UTF_8));
        }

        List<String> stored = new ArrayList<>(List.of(UNMASKED_CPF, PARTIAL_PARTNER, UNRELATED_PARTNER, emailCanary,
                STUB_SENTINEL));
        stored.addAll(tokens);
        for (String collection : mongo.getCollectionNames()) {
            for (Document doc : mongo.getCollection(collection).find()) {
                String text = doc.toJson();
                for (String value : stored) {
                    assertThat(text).as(collection).doesNotContain(value);
                }
            }
        }
        for (AuditEvent audit : auditsOf(workspace)) {
            if (audit.resumo() != null && audit.resumo().cnpjMascarado() != null) {
                assertThat(audit.resumo().cnpjMascarado()).contains("*").doesNotContainPattern("\\d{5,}");
            }
        }
    }

    @Test
    @DisplayName("the committed contract declares the five link operations, version 0.2.0 and their codes")
    void theCommittedContract() throws IOException {
        String yaml = Files.readString(Path.of("docs", "openapi.yaml"));

        assertThat(yaml).contains("version: 0.2.0");
        for (String path : List.of("/public/v/{slug}:", "/public/v/{slug}/lookup:", "/public/v/{slug}/verify:",
                "/public/v/{slug}/cases/{token}:", "/public/v/{slug}/cases/{token}/contact:")) {
            assertThat(yaml).contains(path);
        }
        for (String code : List.of("LINK_NAO_ENCONTRADO", "EMPRESA_NAO_ENCONTRADA", "CNPJ_INVALIDO",
                "RESPONSAVEL_INVALIDO", "VALOR_INVALIDO", "LIMITE_CONSULTAS", "LIMITE_CNPJ", "LIMITE_TENTATIVAS",
                "CASO_NAO_ENCONTRADO", "EMAIL_INVALIDO", "CASO_DECIDIDO", "AUTOFILL_DESATIVADO", "INDISPONIVEL",
                "BFF_KEY_AUSENTE", "Idempotency-Key", "email_contato_mascarado", "consultado_em", "prazo_revisao")) {
            assertThat(yaml).as(code).contains(code);
        }
    }
}
