package com.matheusmelo.kybr.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.EstadoProcessamento;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.MotivoRevisao;
import com.matheusmelo.kybr.model.link.Processamento;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.LimiterClock;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/** E04: reading a case by its token. */
class LinkCaseIT extends WebIntegrationTest {

    private static final String CLIENT_IP_HEADER = "X-Kybr-Client-IP";

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private LinkCaseRepository cases;

    private Workspace workspace;
    private String clientIp;

    @BeforeEach
    void setUp() {
        workspace = workspaces.save(TestWorkspaces.builder().build());
        clientIp = "198.51." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256);
    }

    @Test
    void aFreshCaseIsProcessingWithItsCreationTime() {
        TestCases.Built built = save(TestCases.builder(workspace));

        Response response = read(workspace.slug(), built.token());

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = response.jsonPath().getMap("");
        assertThat(body).containsOnlyKeys("estado", "criado_em");
        assertThat(body.get("estado")).isEqualTo("processing");
        assertThat((String) body.get("criado_em")).matches("\\d{4}-\\d{2}-\\d{2}T.+Z");
    }

    @ParameterizedTest
    @EnumSource(MotivoRevisao.class)
    void aCaseInManualReviewNeverShowsItsReviewReason(MotivoRevisao motivo) {
        TestCases.Built built = save(TestCases.builder(workspace).processamento(new Processamento(
                EstadoProcessamento.REVISAO_MANUAL, 1, null, null, null, motivo)));

        Response response = read(workspace.slug(), built.token());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getMap("")).containsOnlyKeys("estado", "criado_em");
        assertThat(response.jsonPath().getString("estado")).isEqualTo("processing");
        assertThat(response.asString()).doesNotContain(motivo.name()).doesNotContain("motivo_revisao")
                .doesNotContain("REVISAO_MANUAL");
    }

    @Test
    void anApprovedCaseCarriesItsResult() {
        Resultado approved = new Resultado("EMPRESA EXEMPLO LTDA", Instant.parse("2026-11-07T14:03:11Z"), null, null,
                List.of(ItemResultado.of(CodigoResultado.SITUACAO_ATIVA)), null, null);
        TestCases.Built built = save(TestCases.builder(workspace).estado(EstadoCaso.approved).resultado(approved));

        Response response = read(workspace.slug(), built.token());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("estado")).isEqualTo("approved");
        assertThat(response.jsonPath().getMap("resultado")).containsOnlyKeys("razao_social", "responsavel",
                "consultado_em", "confirmacoes");
        assertThat(response.jsonPath().getString("resultado.consultado_em")).isEqualTo("2026-11-07T14:03:11Z");
    }

    @Test
    void anUnknownSlugIsLinkNotFoundWhateverTheToken() {
        TestCases.Built built = save(TestCases.builder(workspace));

        Response withRealToken = read("loja-" + UUID.randomUUID(), built.token());
        Response withGarbage = read("loja-" + UUID.randomUUID(), "garbage");

        assertError(withRealToken, 404, "LINK_NAO_ENCONTRADO");
        assertThat(withGarbage.asByteArray()).isEqualTo(withRealToken.asByteArray());
    }

    @Test
    void unknownForeignAndOffPatternTokensAreTheSameCaseNotFound() {
        Workspace other = workspaces.save(TestWorkspaces.builder().build());
        TestCases.Built foreign = save(TestCases.builder(other));

        Response unknown = read(workspace.slug(), TestCases.newToken());
        Response fromOtherSlug = read(workspace.slug(), foreign.token());
        Response offPattern = read(workspace.slug(), "a".repeat(21));

        assertError(unknown, 404, "CASO_NAO_ENCONTRADO");
        assertThat(fromOtherSlug.asByteArray()).isEqualTo(unknown.asByteArray());
        assertThat(fromOtherSlug.headers().asList().stream().map(h -> h.getName()).sorted().toList())
                .isEqualTo(unknown.headers().asList().stream().map(h -> h.getName()).sorted().toList());
        assertThat(offPattern.asByteArray()).isEqualTo(unknown.asByteArray());
    }

    @Test
    void aDisabledLinkStillServesAnExistingCase() {
        Workspace disabled = workspaces.save(TestWorkspaces.builder().linkAtivo(false).build());
        TestCases.Built built = save(TestCases.builder(disabled));

        assertThat(read(disabled.slug(), built.token()).statusCode()).isEqualTo(200);
    }

    @AfterEach
    void releaseTheLimiterClock() {
        LimiterClock.INSTANCE.release();
    }

    @Test
    void theCaseReadLimitIs429WithRetryAfter() {
        // Mid-window and a little ahead, so every call shares one window and the counter outlives the test.
        LimiterClock.INSTANCE.freeze(Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofSeconds(150)));
        TestCases.Built built = save(TestCases.builder(workspace));
        for (int i = 0; i < 300; i++) {
            assertThat(read(workspace.slug(), built.token()).statusCode()).as("call %d", i + 1).isEqualTo(200);
        }

        Response throttled = read(workspace.slug(), built.token());

        assertError(throttled, 429, "LIMITE_TENTATIVAS");
        assertThat(throttled.header("Retry-After")).isEqualTo("30");
    }

    private TestCases.Built save(TestCases.Builder builder) {
        TestCases.Built built = builder.build();
        cases.save(built.linkCase());
        return built;
    }

    private Response read(String slug, String token) {
        return http().header(CLIENT_IP_HEADER, clientIp).get("/public/v/{slug}/cases/{token}", slug, token);
    }

    private static void assertError(Response response, int status, String code) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        assertThat(response.jsonPath().getString("code")).isEqualTo(code);
    }
}
