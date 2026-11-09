package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The contract of the seller document upload (E06) and the case read's new optional field, version 0.4.0. */
class UploadContractIT extends WebIntegrationTest {

    private static final String UPLOAD = "paths.'/public/v/{slug}/cases/{token}/documents'.post";
    private static final String BODY = "components.schemas.UploadForm";

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
    void theUploadListsItsSuccessSchemaAndEveryErrorCode() {
        assertThat(docs.getString(UPLOAD + ".responses.'202'.content.'application/json'.schema.'$ref'"))
                .isEqualTo("#/components/schemas/UploadResponse");
        assertThat(docs.<String, Object>getMap(UPLOAD + ".responses")).doesNotContainKey("201");
        assertThat(errorCodes()).isEqualTo(Map.of(
                "400", List.of("REQUISICAO_INVALIDA"),
                "404", List.of("LINK_NAO_ENCONTRADO", "CASO_NAO_ENCONTRADO"),
                "406", List.of("FORMATO_NAO_ACEITO"),
                "409", List.of("CASO_DECIDIDO"),
                "413", List.of("ARQUIVO_GRANDE", "LIMITE_ARQUIVOS", "REQUISICAO_GRANDE"),
                "415", List.of("FORMATO_INVALIDO", "MIDIA_NAO_SUPORTADA"),
                "429", List.of("LIMITE_TENTATIVAS"),
                "500", List.of("ERRO_INTERNO"),
                "503", List.of("INDISPONIVEL")));
        assertThat(docs.<String, Object>getMap(UPLOAD + ".responses")).doesNotContainKey("403");
    }

    @Test
    void theBodyIsMultipartWithOneTypeAndRepeatedBinaryFiles() {
        assertThat(docs.getString(UPLOAD + ".requestBody.content.'multipart/form-data'.schema.'$ref'"))
                .isEqualTo("#/components/schemas/UploadForm");
        assertThat(docs.getBoolean(UPLOAD + ".requestBody.required")).isTrue();
        assertThat(docs.getList(BODY + ".required", String.class)).containsExactlyInAnyOrder("tipo", "arquivos");
        assertThat(docs.getList(BODY + ".properties.tipo.enum", String.class)).containsExactly(
                "CONTRATO_SOCIAL_OU_CCMEI", "PROCURACAO", "COMPROVANTE_ENDERECO", "CARTAO_CNPJ", "RESPONSAVEL");
        assertThat(docs.getString(BODY + ".properties.arquivos.type")).isEqualTo("array");
        assertThat(docs.getString(BODY + ".properties.arquivos.items.type")).isEqualTo("string");
        assertThat(docs.getString(BODY + ".properties.arquivos.items.format")).isEqualTo("binary");
        assertThat(docs.getInt(BODY + ".properties.arquivos.maxItems")).isEqualTo(30);
        assertThat(docs.getList("components.schemas.UploadResponse.required", String.class))
                .containsExactlyInAnyOrder("tipo", "documentos_enviados", "situacao_envio");
        assertThat(docs.getList("components.schemas.UploadResponse.properties.situacao_envio.enum", String.class))
                .containsExactly("verificando", "aceito", "recusado");
    }

    @Test
    void caseReadDocumentItemsCarryTheirVerificationStateWithGenericReasons() {
        String item = "components.schemas.Item.properties";
        assertThat(docs.getList(item + ".situacao_envio.enum", String.class))
                .containsExactly("verificando", "aceito", "recusado");
        assertThat(docs.getList(item + ".motivo_recusa.enum", String.class))
                .containsExactly("CONTEUDO_NAO_PERMITIDO", "ARQUIVO_INVALIDO", "VERIFICACAO_FALHOU");
        assertThat(docs.getString(item + ".documentos_recusados.type")).isEqualTo("integer");
        assertThat(docs.getString(item + ".situacao_envio.description")).contains("poll");
        assertThat(docs.prettify()).doesNotContainIgnoringCase("malware");
    }

    @Test
    void theUploadTakesNoBffKeyOrClientIpHeader() {
        assertThat(docs.getList(UPLOAD + ".parameters.'$ref'", String.class))
                .doesNotContain("#/components/parameters/X-Kybr-BFF-Key", "#/components/parameters/X-Kybr-Client-IP");
        assertThat(docs.getList(UPLOAD + ".parameters.name", String.class)).containsExactlyInAnyOrder("slug", "token");
    }

    @Test
    void theDescriptionsStateTheBrowserCallTheByteAndFileRulesAndTheFormatRules() {
        String description = docs.getString(UPLOAD + ".description");

        assertThat(description).contains("browser").contains("no `X-Kybr-BFF-Key`").contains("credentials")
                .contains("Origin").contains("not a control").contains("CORS")
                .contains("10,485,760").contains("30 files").contains("first bytes")
                .contains("one PDF").contains("ignored").contains("send these as PDF");
        assertThat(docs.getString("info.description")).contains("/documents");
        assertThat(docs.getString(UPLOAD + ".responses.'413'.description")).contains("10,485,760")
                .contains("30");
        assertThat(docs.getString(UPLOAD + ".responses.'503'.description")).contains("Retry-After");
    }

    @Test
    void caseItemsCarryTheOptionalUploadFormat() {
        assertThat(docs.getList("components.schemas.Item.properties.formato_envio.enum", String.class))
                .containsExactly("pdf", "fotos");
        assertThat(docs.getList("components.schemas.Item.required", String.class)).doesNotContain("formato_envio");
    }

    /** Status → the codes named by that response's examples, for every 4xx and 5xx response. */
    private Map<String, List<String>> errorCodes() {
        Map<String, Object> responses = docs.getMap(UPLOAD + ".responses");
        Map<String, List<String>> codes = new TreeMap<>();
        responses.keySet().stream().filter(status -> status.matches("[45]\\d\\d")).forEach(status -> codes.put(status,
                List.copyOf(docs.<String, Object>getMap(UPLOAD + ".responses.'" + status
                        + "'.content.'application/json'.examples").keySet())));
        return codes;
    }
}
