package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.matheusmelo.kybr.model.link.Regime;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Found;
import com.matheusmelo.kybr.service.company.CompanyDataResult.NotFound;
import com.matheusmelo.kybr.service.company.CompanyDataResult.RateLimited;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unavailable;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unsupported;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class CnpjaCompanyDataProviderTest {

    private static final String BASE = "https://provider.example.test";
    private static final String CNPJ = "12345678000195";
    private static final String SENTINEL = "synthetic-body-sentinel";

    /** Generated per run: no key material in the repository. */
    private final String apiKey = randomKey();
    private MockRestServiceServer server;
    private CnpjaCompanyDataProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        CnpjaCompanyDataProvider.Settings settings = new CnpjaCompanyDataProvider.Settings(URI.create(BASE), apiKey,
                "Authorization", "CACHE_IF_FRESH", 45, true, "BR", Duration.ofSeconds(2), Duration.ofSeconds(3),
                Duration.ofSeconds(8));
        provider = new CnpjaCompanyDataProvider(settings, client, client);
    }

    @Test
    void basicSendsTheKeyInTheConfiguredHeaderWithTheCacheParametersAndMapsA200() {
        server.expect(requestTo(BASE + "/office/" + CNPJ + "?strategy=CACHE_IF_FRESH&maxAge=45"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", apiKey))
                .andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));

        CompanyDataResult<BasicCompany> result = provider.basic(CNPJ);

        server.verify();
        assertThat(result).isEqualTo(new Found<>(new BasicCompany("Loja Sintetica Exemplo Ltda", "São Paulo", "SP",
                "Comércio varejista de artigos")));
    }

    @Test
    void fullCheckAsksForTheAddOnsAndMapsTheFullRecord() {
        server.expect(requestTo(BASE + "/office/" + CNPJ
                        + "?strategy=CACHE_IF_FRESH&maxAge=45&simples=true&registrations=BR"))
                .andExpect(queryParam("simples", "true"))
                .andExpect(header("Authorization", apiKey))
                .andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));

        CompanyRecord record = ((Found<CompanyRecord>) provider.fullCheck(CNPJ)).company();

        assertThat(record.situacao()).isEqualTo(SituacaoCadastral.ativa);
        assertThat(record.dataAbertura()).isEqualTo(LocalDate.of(2015, 3, 2));
        assertThat(record.regime()).isEqualTo(Regime.SIMPLES);
        assertThat(record.cnaeCodigo()).isEqualTo("4789099");
        assertThat(record.qsaNomes()).containsExactly("Maria Exemplo Teste");
        assertThat(record.inscricoesEstaduais()).containsExactly(new CompanyRecord.InscricaoEstadual("SP", true));
    }

    @Test
    void a200MissingCityOrActivityDescriptionMapsThoseFieldsToNull() {
        String incomplete = body().replace("\"city\": \"São Paulo\",", "").replace("\"text\": \"Comércio varejista de artigos\"",
                "\"text\": \"  \"");
        server.expect(ExpectedCount.once(), requestTo(BASE + "/office/" + CNPJ + "?strategy=CACHE_IF_FRESH&maxAge=45"))
                .andRespond(withSuccess(incomplete, MediaType.APPLICATION_JSON));

        BasicCompany company = ((Found<BasicCompany>) provider.basic(CNPJ)).company();

        assertThat(company.municipio()).isNull();
        assertThat(company.cnaeDescricao()).isNull();
        assertThat(company.razaoSocial()).isEqualTo("Loja Sintetica Exemplo Ltda");
    }

    @Test
    void aLowerCaseStateIsUpperCased() {
        respond(withSuccess(body().replace("\"state\": \"SP\" }", "\"state\": \"sp\" }"), MediaType.APPLICATION_JSON));

        assertThat(((Found<BasicCompany>) provider.basic(CNPJ)).company().uf()).isEqualTo("SP");
    }

    @ParameterizedTest
    @ValueSource(strings = {"XX", "S1", "SPX", "<b>"})
    void aStateThatIsNotABrazilianStateCodeMapsToNull(String state) {
        respond(withSuccess(body().replace("\"state\": \"SP\" }", "\"state\": \"" + state + "\" }"),
                MediaType.APPLICATION_JSON));

        assertThat(((Found<BasicCompany>) provider.basic(CNPJ)).company().uf()).isNull();
    }

    @Test
    void overlongTextFieldsMapToNull() {
        String overlong = body()
                .replace("\"Loja Sintetica Exemplo Ltda\"", "\"" + "L".repeat(301) + "\"")
                .replace("\"São Paulo\"", "\"" + "S".repeat(121) + "\"")
                .replace("\"Comércio varejista de artigos\"", "\"" + "C".repeat(301) + "\"");
        respond(withSuccess(overlong, MediaType.APPLICATION_JSON));

        BasicCompany company = ((Found<BasicCompany>) provider.basic(CNPJ)).company();

        assertThat(company.razaoSocial()).isNull();
        assertThat(company.municipio()).isNull();
        assertThat(company.cnaeDescricao()).isNull();
    }

    @Test
    void textFieldsAtTheirLengthLimitAreKept() {
        String atLimit = body()
                .replace("\"Loja Sintetica Exemplo Ltda\"", "\"" + "L".repeat(300) + "\"")
                .replace("\"São Paulo\"", "\"" + "S".repeat(120) + "\"");
        respond(withSuccess(atLimit, MediaType.APPLICATION_JSON));

        BasicCompany company = ((Found<BasicCompany>) provider.basic(CNPJ)).company();

        assertThat(company.razaoSocial()).hasSize(300);
        assertThat(company.municipio()).hasSize(120);
    }

    @Test
    void aFullCheckDropsAnInvalidRegistrationStateAndAMalformedActivityCode() {
        String malformed = body().replace("\"registrations\": [ { \"state\": \"SP\"",
                "\"registrations\": [ { \"state\": \"ZZ\"").replace("\"id\": 4789099", "\"id\": \"47-89\"");
        server.expect(requestTo(BASE + "/office/" + CNPJ
                        + "?strategy=CACHE_IF_FRESH&maxAge=45&simples=true&registrations=BR"))
                .andRespond(withSuccess(malformed, MediaType.APPLICATION_JSON));

        CompanyRecord record = ((Found<CompanyRecord>) provider.fullCheck(CNPJ)).company();

        assertThat(record.inscricoesEstaduais()).containsExactly(new CompanyRecord.InscricaoEstadual(null, true));
        assertThat(record.cnaeCodigo()).isNull();
    }

    @Test
    void notFoundIs404() {
        respond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(provider.basic(CNPJ)).isInstanceOf(NotFound.class);
    }

    @Test
    void a400OnAnAlphanumericCnpjIsUnsupported() {
        server.expect(requestTo(BASE + "/office/12ABC34501DE35?strategy=CACHE_IF_FRESH&maxAge=45"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThat(provider.basic("12ABC34501DE35")).isInstanceOf(Unsupported.class);
    }

    @Test
    void a400OnANumericCnpjIsUnavailable() {
        respond(withStatus(HttpStatus.BAD_REQUEST));
        assertThat(provider.basic(CNPJ)).isInstanceOf(Unavailable.class);
    }

    @Test
    void a429IsRateLimitedForItsRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "17");
        respond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));
        assertThat(provider.basic(CNPJ)).isEqualTo(new RateLimited<>(Duration.ofSeconds(17)));
    }

    @Test
    void a429WithoutAUsableRetryAfterWaitsAMinute() {
        respond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(provider.basic(CNPJ)).isEqualTo(new RateLimited<>(Duration.ofSeconds(60)));
    }

    @Test
    void aServerErrorIsUnavailable() {
        respond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(provider.basic(CNPJ)).isInstanceOf(Unavailable.class);
    }

    @Test
    void aTimeoutIsUnavailable() {
        respond(withException(new SocketTimeoutException("read timed out")));
        assertThat(provider.basic(CNPJ)).isInstanceOf(Unavailable.class);
    }

    @Test
    void anUnreadableBodyIsUnavailable() {
        respond(withSuccess("{not json", MediaType.APPLICATION_JSON));
        assertThat(provider.basic(CNPJ)).isInstanceOf(Unavailable.class);
    }

    @Test
    void aBodyOverTheSizeCapIsUnavailableAndIsNeverParsed(CapturedOutput output) {
        String oversized = body().replaceFirst("\\{", "{ \"padding\": \"" + "x".repeat(1024 * 1024) + "\",");
        respond(withSuccess(oversized, MediaType.APPLICATION_JSON));

        assertThat(provider.basic(CNPJ)).isInstanceOf(Unavailable.class);
        assertThat(output.getAll()).contains("event=provider_error").contains("error=body_too_large")
                .doesNotContain(SENTINEL);
    }

    @Test
    void aBodyJustUnderTheSizeCapIsRead() {
        String padded = body().replaceFirst("\\{", "{ \"padding\": \"" + "x".repeat(1000 * 1000) + "\",");
        respond(withSuccess(padded, MediaType.APPLICATION_JSON));

        assertThat(provider.basic(CNPJ)).isInstanceOf(Found.class);
    }

    @Test
    void failuresAreLoggedAsTypeAndStatusOnlyWithNoKeyUrlOrBody(CapturedOutput output) {
        respond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(SENTINEL));
        provider.basic(CNPJ);
        server.reset();
        respond(withException(new SocketTimeoutException("timed out reading " + BASE)));
        provider.basic(CNPJ);

        assertThat(output.getAll()).contains("event=provider_error").contains("tipo=basic").contains("status=503")
                .doesNotContain(apiKey).doesNotContain(SENTINEL).doesNotContain(BASE).doesNotContain(CNPJ);
    }

    @Test
    void theKeyNeverAppearsInTheClientsTextForm() {
        assertThat(provider.toString()).doesNotContain(apiKey);
    }

    private void respond(org.springframework.test.web.client.ResponseCreator creator) {
        server.expect(requestTo(BASE + "/office/" + CNPJ + "?strategy=CACHE_IF_FRESH&maxAge=45")).andRespond(creator);
    }

    private static String body() {
        return """
                {
                  "_comment": "synthetic-body-sentinel",
                  "taxId": "12345678000195",
                  "company": {
                    "name": "Loja Sintetica Exemplo Ltda",
                    "simples": { "optant": true },
                    "simei": { "optant": false },
                    "members": [ { "person": { "type": "NATURAL", "name": "Maria Exemplo Teste" } } ]
                  },
                  "founded": "2015-03-02",
                  "status": { "id": 2, "text": "Ativa" },
                  "address": { "city": "São Paulo", "state": "SP" },
                  "mainActivity": { "id": 4789099, "text": "Comércio varejista de artigos" },
                  "registrations": [ { "state": "SP", "enabled": true } ]
                }
                """;
    }

    private static String randomKey() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
