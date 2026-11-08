package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The seller-link operations in the generated contract: each lists its success schema and every
 * error code it can return (one named example per code, under its status), the request headers,
 * and the rules integrators rely on.
 */
class LinkContractIT extends WebIntegrationTest {

    private static final String INFO = "paths.'/public/v/{slug}'.get";
    private static final String LOOKUP = "paths.'/public/v/{slug}/lookup'.get";
    private static final String VERIFY = "paths.'/public/v/{slug}/verify'.post";
    private static final String CASE = "paths.'/public/v/{slug}/cases/{token}'.get";
    private static final String CONTACT = "paths.'/public/v/{slug}/cases/{token}/contact'.put";

    private JsonPath docs;

    @BeforeEach
    void load() {
        docs = http().get("/v3/api-docs").jsonPath();
    }

    @Test
    void theContractVersionIs040() {
        assertThat(docs.getString("info.version")).isEqualTo("0.4.0");
    }

    @Test
    void linkInfoListsItsSuccessSchemaAndEveryErrorCode() {
        assertThat(successSchema(INFO, "200")).isEqualTo("#/components/schemas/LinkInfoResponse");
        assertThat(errorCodes(INFO)).isEqualTo(Map.of(
                "400", List.of("REQUISICAO_INVALIDA"),
                "403", List.of("BFF_KEY_AUSENTE"),
                "404", List.of("LINK_NAO_ENCONTRADO"),
                "406", List.of("FORMATO_NAO_ACEITO"),
                "500", List.of("ERRO_INTERNO"),
                "503", List.of("INDISPONIVEL")));
    }

    @Test
    void lookupListsItsSuccessSchemaAndEveryErrorCode() {
        assertThat(successSchema(LOOKUP, "200")).isEqualTo("#/components/schemas/LookupResponse");
        assertThat(errorCodes(LOOKUP)).isEqualTo(Map.of(
                "400", List.of("CNPJ_INVALIDO", "REQUISICAO_INVALIDA"),
                "403", List.of("BFF_KEY_AUSENTE"),
                "404", List.of("LINK_NAO_ENCONTRADO", "EMPRESA_NAO_ENCONTRADA"),
                "406", List.of("FORMATO_NAO_ACEITO"),
                "429", List.of("LIMITE_CONSULTAS"),
                "500", List.of("ERRO_INTERNO"),
                "503", List.of("AUTOFILL_DESATIVADO", "INDISPONIVEL")));
    }

    @Test
    void lookupTakesOneRequiredCanonicalCnpjQueryParameterAndAnswersFourRequiredFields() {
        Map<String, Object> cnpj = docs.getMap(LOOKUP + ".parameters.find { it.name == 'cnpj' }");

        assertThat(cnpj).containsEntry("in", "query").containsEntry("required", true);
        assertThat(docs.getString(LOOKUP + ".parameters.find { it.name == 'cnpj' }.schema.pattern"))
                .isEqualTo("^[0-9A-Z]{12}[0-9]{2}$");
        assertThat(docs.getString(LOOKUP + ".responses.'400'.description")).contains("400 here");
        assertThat(required("LookupResponse")).containsExactlyInAnyOrder("razao_social", "municipio", "uf", "cnae");
        assertThat(required("Cnae")).containsExactly("descricao");
        assertThat(docs.getString("components.schemas.LookupResponse.properties.uf.pattern")).isEqualTo("^[A-Z]{2}$");
    }

    @Test
    void verifyListsItsSuccessSchemaAndEveryErrorCode() {
        assertThat(successSchema(VERIFY, "201")).isEqualTo("#/components/schemas/VerifyResponse");
        assertThat(errorCodes(VERIFY)).isEqualTo(Map.of(
                "400", List.of("REQUISICAO_INVALIDA"),
                "403", List.of("BFF_KEY_AUSENTE"),
                "404", List.of("LINK_NAO_ENCONTRADO"),
                "406", List.of("FORMATO_NAO_ACEITO"),
                "413", List.of("REQUISICAO_GRANDE"),
                "415", List.of("MIDIA_NAO_SUPORTADA"),
                "422", List.of("CNPJ_INVALIDO", "RESPONSAVEL_INVALIDO", "VALOR_INVALIDO"),
                "429", List.of("LIMITE_CNPJ", "LIMITE_TENTATIVAS"),
                "500", List.of("ERRO_INTERNO"),
                "503", List.of("INDISPONIVEL")));
    }

    @Test
    void caseReadListsItsSuccessSchemaAndEveryErrorCode() {
        assertThat(successSchema(CASE, "200")).isEqualTo("#/components/schemas/CaseResponse");
        assertThat(errorCodes(CASE)).isEqualTo(Map.of(
                "400", List.of("REQUISICAO_INVALIDA"),
                "403", List.of("BFF_KEY_AUSENTE"),
                "404", List.of("LINK_NAO_ENCONTRADO", "CASO_NAO_ENCONTRADO"),
                "406", List.of("FORMATO_NAO_ACEITO"),
                "429", List.of("LIMITE_TENTATIVAS"),
                "500", List.of("ERRO_INTERNO"),
                "503", List.of("INDISPONIVEL")));
    }

    @Test
    void contactChangeListsItsNoContentSuccessAndEveryErrorCode() {
        assertThat(docs.getMap(CONTACT + ".responses.'204'")).doesNotContainKey("content");
        assertThat(errorCodes(CONTACT)).isEqualTo(Map.of(
                "400", List.of("REQUISICAO_INVALIDA"),
                "403", List.of("BFF_KEY_AUSENTE"),
                "404", List.of("LINK_NAO_ENCONTRADO", "CASO_NAO_ENCONTRADO"),
                "409", List.of("CASO_DECIDIDO"),
                "413", List.of("REQUISICAO_GRANDE"),
                "415", List.of("MIDIA_NAO_SUPORTADA"),
                "422", List.of("EMAIL_INVALIDO", "VALOR_INVALIDO"),
                "429", List.of("LIMITE_TENTATIVAS"),
                "500", List.of("ERRO_INTERNO"),
                "503", List.of("INDISPONIVEL")));
    }

    @Test
    void eachErrorExampleIsTheEnvelopeWithTheCodesFixedMessage() {
        Map<String, Object> example = docs.getMap(INFO + ".responses.'404'.content.'application/json'.examples"
                + ".LINK_NAO_ENCONTRADO.value");

        assertThat(example).containsEntry("code", "LINK_NAO_ENCONTRADO").containsEntry("message", "Link not found.");
        assertThat(docs.getString(INFO + ".responses.'404'.content.'application/json'.schema.'$ref'"))
                .isEqualTo("#/components/schemas/ErrorResponse");
    }

    @Test
    void verifyRequiresAUuidIdempotencyKeyBoundToTheSlugAndBodyForTenMinutes() {
        Map<String, Object> key = header(VERIFY, "Idempotency-Key");

        assertThat(key).containsEntry("required", true);
        assertThat(docs.getString(VERIFY + ".parameters.find { it.name == 'Idempotency-Key' }.schema.format"))
                .isEqualTo("uuid");
        assertThat((String) key.get("description")).contains("10 minutes").contains("slug").contains("body");
        assertThat((String) key.get("description")).as("the replay rule the server applies")
                .contains("4xx other than 401, 408 and 429").doesNotContain("never replayed");
    }

    @Test
    void everyOperationDocumentsTheBffKeyAndTheCallsThatAreRateLimitedOrAuditedTheClientIp() {
        for (String operation : List.of(INFO, LOOKUP, VERIFY, CASE, CONTACT)) {
            assertThat(docs.getString(operation + ".parameters.find { it.'$ref' == "
                    + "'#/components/parameters/X-Kybr-BFF-Key' }.'$ref'")).as(operation).isNotNull();
        }
        for (String operation : List.of(LOOKUP, VERIFY, CASE, CONTACT)) {
            assertThat(docs.getString(operation + ".parameters.find { it.'$ref' == "
                    + "'#/components/parameters/X-Kybr-Client-IP' }.'$ref'")).as(operation).isNotNull();
        }
        assertThat(docs.getString(INFO + ".parameters.find { it.'$ref' == "
                + "'#/components/parameters/X-Kybr-Client-IP' }")).isNull();
        assertThat(docs.<String, Object>getMap("components.parameters.X-Kybr-BFF-Key"))
                .containsEntry("in", "header").containsEntry("required", true);
        assertThat(docs.<String, Object>getMap("components.parameters.X-Kybr-Client-IP"))
                .containsEntry("in", "header").containsEntry("required", false);
    }

    @Test
    void theDescriptionsStateTheBasePathTheSlugAloneRuleAndThatExtIdIsUnverified() {
        assertThat(docs.getString("info.description")).contains("/public/v/{slug}").contains("unversioned")
                .contains("/public/v2/");
        for (String operation : List.of(INFO, LOOKUP, VERIFY, CASE, CONTACT)) {
            assertThat(docs.getString(operation + ".responses.'404'.description")).as(operation)
                    .contains("from the slug alone");
        }
        assertThat(docs.getString("components.schemas.VerifyRequest.properties.ext_id.description"))
                .contains("not verified").contains("CNPJ");
        assertThat(docs.getString(VERIFY + ".responses.'422'.description")).contains("422 here");
    }

    @Test
    void everyTimestampIsADateTimeWithAnOffsetInItsExample() {
        for (String property : List.of("components.schemas.CaseResponse.properties.criado_em",
                "components.schemas.ResultadoPublico.properties.consultado_em",
                "components.schemas.ResultadoPublico.properties.prazo_revisao")) {
            assertThat(docs.getString(property + ".format")).as(property).isEqualTo("date-time");
            assertThat(docs.getString(property + ".example")).as(property)
                    .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
        }
    }

    @Test
    void responseSchemasMarkTheFieldsTheClientCanRelyOnAsRequired() {
        assertThat(required("LinkInfoResponse")).containsExactlyInAnyOrder("nome", "contato_vendedor");
        assertThat(required("ContatoVendedor")).containsExactlyInAnyOrder("tipo", "valor");
        assertThat(required("VerifyResponse")).containsExactly("token");
        assertThat(required("CaseResponse")).containsExactlyInAnyOrder("estado", "criado_em");
        assertThat(required("ResultadoPublico")).containsExactly("responsavel");
        assertThat(required("ContactRequest")).containsExactly("email");
        assertThat(docs.getString("components.schemas.VerifyResponse.properties.token.pattern"))
                .isEqualTo("^[A-Za-z0-9_-]{22,64}$");
        assertThat(docs.getString("components.schemas.VerifyRequest.properties.cnpj.pattern"))
                .isEqualTo("^[0-9A-Z]{12}[0-9]{2}$");
    }

    private String successSchema(String operation, String status) {
        return docs.getString(operation + ".responses.'" + status + "'.content.'application/json'.schema.'$ref'");
    }

    /** Status → the codes named by that response's examples, for every 4xx and 5xx response. */
    private Map<String, List<String>> errorCodes(String operation) {
        Map<String, Object> responses = docs.getMap(operation + ".responses");
        Map<String, List<String>> codes = new java.util.TreeMap<>();
        responses.keySet().stream().filter(status -> status.matches("[45]\\d\\d")).forEach(status -> codes.put(
                status, List.copyOf(docs.<String, Object>getMap(operation + ".responses.'" + status
                        + "'.content.'application/json'.examples").keySet())));
        return codes;
    }

    private Map<String, Object> header(String operation, String name) {
        return docs.getMap(operation + ".parameters.find { it.name == '" + name + "' }");
    }

    private List<String> required(String schema) {
        return docs.getList("components.schemas." + schema + ".required", String.class);
    }
}
