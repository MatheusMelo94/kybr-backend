package com.matheusmelo.kybr.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.Seller;
import com.matheusmelo.kybr.model.workspace.Plano;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.company.RuntimeFlagsService;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import com.matheusmelo.kybr.util.Cnpj;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** E02: the company lookup behind the seller link, against the provider stub's synthetic fixtures. */
@ExtendWith(OutputCaptureExtension.class)
class LinkLookupIT extends WebIntegrationTest {

    private static final String FOUND = "12345678000195";
    private static final String MEI_UNMASKED = "47158263000197";
    private static final String NOT_FOUND = "71438562000160";
    private static final String METRIC = "kybr.link.lookup";

    @Autowired
    private WorkspaceRepository workspaces;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private MeterRegistry meters;
    @Autowired
    private RuntimeFlagsService flags;

    private Workspace workspace;
    private String clientIp;

    @BeforeEach
    void setUp() {
        // The global switches live in the shared database; another test may have left them off.
        flags.setAutofill(true);
        flags.setProviderSpend(true);
        workspace = workspaces.save(TestWorkspaces.builder().build());
        clientIp = "203.0." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256);
    }

    @Test
    void aFoundCompanyAnswersExactlyNameTownStateAndActivity() {
        Response response = lookup(workspace.slug(), FOUND);

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        Map<String, Object> body = response.jsonPath().getMap("");
        assertThat(body).containsOnlyKeys("razao_social", "municipio", "uf", "cnae");
        assertThat(response.jsonPath().getMap("cnae")).containsOnlyKeys("descricao");
        assertThat(response.jsonPath().getString("uf")).matches("^[A-Z]{2}$");
        assertThat(List.of(body.get("razao_social"), body.get("municipio"), response.jsonPath().get("cnae.descricao")))
                .allSatisfy(value -> assertThat((String) value).isNotBlank());
    }

    @Test
    void aMicroEntrepreneursUnmaskedCpfComesOutMasked() {
        Response response = lookup(workspace.slug(), MEI_UNMASKED);

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        assertThat(response.jsonPath().getString("razao_social")).isEqualTo("JOAO CARLOS SILVA ***.***.***-**");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12ABC34501DE36", "12ABC34501DE3A", "00000000000000", "12abc34501de35",
        "12.345.678/0001-95", "", "1234567800019", "123456780001950"})
    void anInvalidCnpjIs400OnTheCnpjField(String cnpj) {
        assertError(lookup(workspace.slug(), cnpj), 400, "CNPJ_INVALIDO", "cnpj");
    }

    @Test
    void aMissingOrRepeatedCnpjIsTheSame400() {
        assertError(client().get("/public/v/{slug}/lookup", workspace.slug()), 400, "CNPJ_INVALIDO", "cnpj");
        assertError(client().queryParam("cnpj", FOUND, "98765432000198")
                .get("/public/v/{slug}/lookup", workspace.slug()), 400, "CNPJ_INVALIDO", "cnpj");
    }

    @Test
    void anInvalidCnpjIsCountedOnceAsInvalidForTheResolvedSlug() {
        lookup(workspace.slug(), "not-a-cnpj");

        assertThat(meters.get(METRIC).tag("slug", workspace.slug()).tag("resultado", "invalid").counter().count())
                .isEqualTo(1);
    }

    @Test
    void anUnknownCompanyIs404() {
        assertError(lookup(workspace.slug(), NOT_FOUND), 404, "EMPRESA_NAO_ENCONTRADA", null);
    }

    @Test
    void anUnknownOrDisabledLinkIs404EvenWithAnInvalidCnpj() {
        Workspace disabled = workspaces.save(TestWorkspaces.builder().linkAtivo(false).build());

        assertError(lookup("loja-" + UUID.randomUUID(), "bad"), 404, "LINK_NAO_ENCONTRADO", null);
        assertError(lookup(disabled.slug(), "bad"), 404, "LINK_NAO_ENCONTRADO", null);
        assertError(lookup(disabled.slug(), FOUND), 404, "LINK_NAO_ENCONTRADO", null);
    }

    @Test
    void autofillSwitchedOffForTheLinkIs503EvenForAnInvalidOrCachedCnpj() {
        lookup(workspace.slug(), FOUND);
        Workspace off = workspaces.save(TestWorkspaces.builder().autofillAtivo(false).build());

        assertError(lookup(off.slug(), "bad"), 503, "AUTOFILL_DESATIVADO", null);
        assertError(lookup(off.slug(), FOUND), 503, "AUTOFILL_DESATIVADO", null);
    }

    @Test
    void aTrialLinkAnswersFromTheCacheAndAMissIs503() {
        lookup(workspace.slug(), FOUND);
        Workspace trial = workspaces.save(TestWorkspaces.builder().plano(Plano.Tipo.teste).build());

        assertThat(lookup(trial.slug(), FOUND).statusCode()).isEqualTo(200);
        assertError(lookup(trial.slug(), uncachedCnpj()), 503, "AUTOFILL_DESATIVADO", null);
    }

    @Test
    void theLookupLimitIs429WithRetryAfter() {
        // Fixed one-minute windows: 11 calls cross at most one boundary, so one of them is denied.
        Response throttled = IntStream.range(0, 11).mapToObj(i -> lookup(workspace.slug(), FOUND))
                .filter(response -> response.statusCode() != 200).findFirst().orElseThrow();

        assertError(throttled, 429, "LIMITE_CONSULTAS", null);
        assertThat(throttled.header("Retry-After")).matches("\\d+");
        assertThat(Integer.parseInt(throttled.header("Retry-After"))).isBetween(1, 60);
    }

    @Test
    void theCnpjNeverReachesTheLogs(CapturedOutput output) {
        lookup(workspace.slug(), MEI_UNMASKED);
        lookup(workspace.slug(), "47158263000198");

        assertThat(output.getAll()).doesNotContain(MEI_UNMASKED).doesNotContain("47158263000198");
    }

    @Test
    void requestsToUnknownSlugsAddNoSlugTagValues() {
        Set<String> random = IntStream.range(0, 50)
                .mapToObj(i -> "loja-" + UUID.randomUUID().toString().substring(0, 8)).collect(Collectors.toSet());

        random.forEach(slug -> lookup(slug, FOUND));

        Set<String> tagged = new HashSet<>();
        for (Meter meter : meters.find(METRIC).meters()) {
            tagged.add(meter.getId().getTag("slug"));
        }
        assertThat(tagged).doesNotContainAnyElementsOf(random);
    }

    @Test
    void aLookupCreatesNoCaseSellerOrAuditRecord() {
        lookup(workspace.slug(), FOUND);
        lookup(workspace.slug(), NOT_FOUND);
        lookup(workspace.slug(), "bad");

        Query mine = Query.query(Criteria.where("workspace_id").is(workspace.id()));
        assertThat(mongo.count(mine, LinkCase.COLLECTION)).isZero();
        assertThat(mongo.count(mine, Seller.COLLECTION)).isZero();
        assertThat(mongo.count(mine, AuditEvent.COLLECTION)).isZero();
    }

    private RequestSpecification client() {
        return http().header("X-Kybr-Client-IP", clientIp);
    }

    private Response lookup(String slug, String cnpj) {
        return client().queryParam("cnpj", cnpj).get("/public/v/{slug}/lookup", slug);
    }

    /** A valid CNPJ that no fixture and no earlier run has cached. */
    private static String uncachedCnpj() {
        while (true) {
            String base = "9" + String.format("%011d", ThreadLocalRandom.current().nextLong(100_000_000_000L));
            for (int digits = 0; digits < 100; digits++) {
                String candidate = base + String.format("%02d", digits);
                if (Cnpj.isValidCanonical(candidate)) {
                    return candidate;
                }
            }
        }
    }

    private static void assertError(Response response, int status, String code, String field) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        assertThat(response.jsonPath().getString("code")).isEqualTo(code);
        assertThat(response.jsonPath().getString("field")).isEqualTo(field);
    }
}
