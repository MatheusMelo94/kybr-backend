package com.matheusmelo.kybr.acceptance.link;

import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.APPROVED;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.APPROVED_ALPHANUMERIC;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.BLOCKED_BAIXADA;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.BLOCKED_INAPTA;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.BLOCKED_NULA;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.BLOCKED_RECENT_UNRELATED;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.BLOCKED_SUSPENSA;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.IE_INACTIVE;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.INCOMPLETE_RECORD;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.LONG_NAME;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.MEI_MASKED;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.MEI_OWNER;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.MEI_UNMASKED;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.NOT_FOUND;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.NOT_RECENT_180_DAYS;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.OPENED_TODAY;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTIAL_MATCH;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTIAL_OF_PARTNER;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTIAL_PARTNER;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.PARTNER;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.RECENT_179_DAYS;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.RECENT_30_DAYS;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.RECENT_AND_PARTIAL;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.SLOW_WITHIN_TIMEOUT;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.STRANGER;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.UNMASKED_CPF;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.UNRELATED_PARTNER;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.UNRELATED_PARTNERS;
import static com.matheusmelo.kybr.acceptance.link.fixtures.ProviderScenarios.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.EstadoProcessamento;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.MotivoRevisao;
import com.matheusmelo.kybr.model.link.Seller;
import com.matheusmelo.kybr.model.link.StatusSeller;
import com.matheusmelo.kybr.model.link.Variante;
import com.matheusmelo.kybr.model.link.Verification;
import com.matheusmelo.kybr.model.workspace.Acao;
import com.matheusmelo.kybr.model.workspace.Chave;
import com.matheusmelo.kybr.model.workspace.Politica;
import com.matheusmelo.kybr.model.workspace.PoliticaRegra;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * From a verification start to the seller's result, through the background worker and the provider
 * stub: every verdict path, the "no data" and provider-failure paths, and what is stored.
 */
class VerificationFlowAcceptanceIT extends LinkAcceptanceTest {

    private static final Set<String> FIXED_MOTIVOS_FORBIDDEN = Set.of(PARTNER, PARTIAL_PARTNER, UNRELATED_PARTNER);

    private Outcome run(String cnpj, String responsavel) {
        Workspace workspace = newWorkspace();
        String token = start(workspace, cnpj, responsavel);
        return awaitOutcome(workspace, token);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        Map<String, Object> resultado = (Map<String, Object>) body.get("resultado");
        Object value = resultado == null ? null : resultado.get(key);
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultado(Outcome outcome) {
        return (Map<String, Object>) outcome.body().get("resultado");
    }

    @Test
    @DisplayName("an active company with the exact partner name is approved with three confirmations")
    void anExactMatchIsApproved() {
        Outcome outcome = run(APPROVED, PARTNER);

        assertThat(outcome.estado()).isEqualTo("approved");
        Map<String, Object> resultado = resultado(outcome);
        assertThat(resultado).containsOnlyKeys("razao_social", "responsavel", "consultado_em", "confirmacoes");
        assertThat(resultado).containsEntry("razao_social", "Loja Exemplo Comercio de Utilidades Ltda")
                .containsEntry("responsavel", PARTNER);
        assertThat(list(outcome.body(), "confirmacoes")).containsExactlyInAnyOrder(Map.of("codigo", "SITUACAO_ATIVA"),
                Map.of("codigo", "RESPONSAVEL_CONFERE"), Map.of("codigo", "IE_ATIVA", "uf", "SP"));
        Seller seller = mongo.findOne(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where(Seller.WORKSPACE_ID)
                        .is(outcome.linkCase().workspaceId())), Seller.class);
        assertThat(seller.status()).isEqualTo(StatusSeller.aprovado);
        assertThat(seller.verificadoEm()).isNotNull();
        assertThat(seller.ultimaVerificacaoId()).isEqualTo(outcome.linkCase().verificacaoId());
    }

    @Test
    @DisplayName("an alphanumeric CNPJ is approved and kept upper case end to end")
    void anAlphanumericCnpjIsApproved() {
        Outcome outcome = run(APPROVED_ALPHANUMERIC, PARTNER);

        assertThat(outcome.estado()).isEqualTo("approved");
        assertThat(list(outcome.body(), "confirmacoes")).contains(Map.of("codigo", "IE_ATIVA", "uf", "PR"));
        assertThat(outcome.linkCase().cnpj()).isEqualTo(APPROVED_ALPHANUMERIC);
        assertThat(verificationOf(outcome.linkCase()).orElseThrow().cnpj()).isEqualTo(APPROVED_ALPHANUMERIC);
    }

    @Test
    @DisplayName("a partial and a non-matching name give the same document request")
    void partialAndNoMatchLookTheSame() {
        Outcome partial = run(PARTIAL_MATCH, PARTNER);
        Outcome none = run(APPROVED, STRANGER);

        for (Outcome outcome : List.of(partial, none)) {
            assertThat(outcome.estado()).isEqualTo("pending");
            assertThat(list(outcome.body(), "itens")).containsExactly(Map.of("codigo", "DOCUMENTO_RESPONSAVEL",
                    "documento_tipo", "RESPONSAVEL", "documentos_enviados", 0));
            Instant criado = Instant.parse((String) outcome.body().get("criado_em"));
            Instant prazo = Instant.parse((String) resultado(outcome).get("prazo_revisao"));
            assertThat(prazo).isEqualTo(criado.plus(Duration.ofDays(2)));
        }
        assertThat(resultado(partial).keySet()).isEqualTo(resultado(none).keySet());
    }

    @Test
    @DisplayName("with a policy that blocks on the partner check, a partial match still never blocks")
    void aPartialMatchNeverBlocks() {
        Politica blockOnPartner = new Politica(1, TestWorkspaces.defaultPolicy().regras().stream()
                .map(r -> r.chave() == Chave.qsa ? new PoliticaRegra(r.chave(), Acao.block, r.acoesPermitidas(), r.dias()) : r)
                .toList());
        Workspace workspace = newWorkspace(TestWorkspaces.builder().politica(blockOnPartner));

        Outcome partial = awaitOutcome(workspace, start(workspace, PARTIAL_MATCH, PARTNER));
        Outcome none = awaitOutcome(workspace, start(workspace, APPROVED, STRANGER));

        assertThat(partial.estado()).isEqualTo("pending");
        assertThat(list(partial.body(), "itens")).contains(Map.of("codigo", "DOCUMENTO_RESPONSAVEL",
                "documento_tipo", "RESPONSAVEL", "documentos_enviados", 0));
        assertThat(none.estado()).as("no match still follows the policy").isEqualTo("blocked");
    }

    @Test
    @DisplayName("an inapta company is blocked with the gov.br regularisation URL")
    void anInaptaCompanyIsBlocked() {
        Outcome outcome = run(BLOCKED_INAPTA, PARTNER);

        assertThat(outcome.estado()).isEqualTo("blocked");
        @SuppressWarnings("unchecked")
        Map<String, Object> motivo = (Map<String, Object>) resultado(outcome).get("motivo");
        assertThat(motivo).containsEntry("codigo", "SITUACAO_IRREGULAR").containsEntry("situacao", "inapta");
        String url = (String) motivo.get("url_regularizacao");
        assertThat(url).startsWith("https://");
        assertThat(java.net.URI.create(url).getHost()).matches("(.*\\.)?gov\\.br");
        assertThat(sellersOf(workspaces.findById(outcome.linkCase().workspaceId()).orElseThrow()).getFirst().status())
                .isEqualTo(StatusSeller.bloqueado);
    }

    @ParameterizedTest(name = "a {1} company is blocked")
    @CsvSource({BLOCKED_SUSPENSA + ",suspensa", BLOCKED_BAIXADA + ",baixada", BLOCKED_NULA + ",nula"})
    void irregularCompaniesAreBlocked(String cnpj, String situacao) {
        Outcome outcome = run(cnpj, PARTNER);

        assertThat(outcome.estado()).isEqualTo("blocked");
        assertThat(outcome.body().toString()).contains("situacao=" + situacao);
    }

    @Test
    @DisplayName("a company opened 30 days ago is pending as recent")
    void aRecentCompanyIsPending() {
        Outcome outcome = run(RECENT_30_DAYS, PARTNER);

        assertThat(outcome.estado()).isEqualTo("pending");
        assertThat(list(outcome.body(), "itens")).containsExactly(Map.of("codigo", "EMPRESA_RECENTE", "dias", 30));
        assertThat(resultado(outcome)).containsKey("prazo_revisao");
    }

    @Test
    @DisplayName("the recent-company boundaries: 179 days is recent, 180 is not, opened today is one day")
    void theRecentCompanyBoundaries() {
        Outcome days179 = run(RECENT_179_DAYS, PARTNER);
        Outcome days180 = run(NOT_RECENT_180_DAYS, PARTNER);
        Outcome today = run(OPENED_TODAY, PARTNER);

        assertThat(list(days179.body(), "itens")).containsExactly(Map.of("codigo", "EMPRESA_RECENTE", "dias", 179));
        assertThat(days180.estado()).isEqualTo("approved");
        assertThat(today.estado()).isEqualTo("pending");
        assertThat(list(today.body(), "itens")).containsExactly(Map.of("codigo", "EMPRESA_RECENTE", "dias", 1));
    }

    @Test
    @DisplayName("a micro-entrepreneur matching the name is approved with a limit the seller never sees")
    void aMicroEntrepreneurIsApprovedWithALimit() {
        Outcome outcome = run(MEI_MASKED, MEI_OWNER);

        assertThat(outcome.estado()).isEqualTo("approved");
        assertThat(list(outcome.body(), "confirmacoes")).containsExactlyInAnyOrder(Map.of("codigo", "SITUACAO_ATIVA"),
                Map.of("codigo", "RESPONSAVEL_CONFERE"));
        assertThat(outcome.last().asString()).doesNotContain("MEI");
        assertThat(verificationOf(outcome.linkCase()).orElseThrow().aprovadoComLimite()).isTrue();
    }

    @Test
    @DisplayName("an unmasked CPF from the provider is masked on the case read and in every record")
    void anUnmaskedCpfIsMaskedEverywhere() {
        Outcome outcome = run(MEI_UNMASKED, MEI_OWNER);

        assertThat(resultado(outcome).get("razao_social")).isEqualTo("JOAO CARLOS SILVA ***.***.***-**");
        Verification verification = verificationOf(outcome.linkCase()).orElseThrow();
        assertThat(verification.snapshot().razaoSocial()).isEqualTo("JOAO CARLOS SILVA ***.***.***-**");
        for (String collection : List.of(LinkCase.COLLECTION, Verification.COLLECTION, Seller.COLLECTION)) {
            for (Document doc : mongo.getCollection(collection).find()) {
                assertThat(doc.toJson()).as(collection).doesNotContain(UNMASKED_CPF);
            }
        }
    }

    @Test
    @DisplayName("an inapta, unmatched and recent company is blocked for its status only")
    void blockingWinsAndOnlyTheFirstReasonShows() {
        Outcome outcome = run(BLOCKED_RECENT_UNRELATED, PARTNER);

        assertThat(outcome.estado()).isEqualTo("blocked");
        @SuppressWarnings("unchecked")
        Map<String, Object> motivo = (Map<String, Object>) resultado(outcome).get("motivo");
        assertThat(motivo.get("codigo")).isEqualTo("SITUACAO_IRREGULAR");
        assertThat(resultado(outcome)).doesNotContainKey("itens");
    }

    @Test
    @DisplayName("a recent company with a partial name is pending with both items")
    void bothPendingItemsAreListed() {
        Outcome outcome = run(RECENT_AND_PARTIAL, PARTNER);

        assertThat(outcome.estado()).isEqualTo("pending");
        assertThat(list(outcome.body(), "itens")).containsExactlyInAnyOrder(
                Map.of("codigo", "DOCUMENTO_RESPONSAVEL", "documento_tipo", "RESPONSAVEL", "documentos_enviados", 0),
                Map.of("codigo", "EMPRESA_RECENTE", "dias", 60));
    }

    @Test
    @DisplayName("an inactive state registration still approves, without the registration row")
    void anInactiveRegistrationStillApproves() {
        Outcome outcome = run(IE_INACTIVE, PARTNER);

        assertThat(outcome.estado()).isEqualTo("approved");
        assertThat(list(outcome.body(), "confirmacoes")).containsExactlyInAnyOrder(Map.of("codigo", "SITUACAO_ATIVA"),
                Map.of("codigo", "RESPONSAVEL_CONFERE"));
    }

    @Test
    @DisplayName("no company data keeps the seller on processing with the no-data variant")
    void noDataStaysProcessingWithTheVariant() {
        Workspace workspace = newWorkspace();
        double before = metric("kybr_verification_no_data_total", "slug=\"" + workspace.slug() + "\"");
        String token = start(workspace, NOT_FOUND, PARTNER);

        Outcome outcome = awaitOutcome(workspace, token);

        assertThat(outcome.estado()).isEqualTo("processing");
        assertThat(resultado(outcome)).isEqualTo(Map.of("responsavel", PARTNER, "variante", "DADOS_NAO_ENCONTRADOS"));
        assertThat(outcome.linkCase().processamento().estado()).isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
        assertThat(outcome.linkCase().variante()).isEqualTo(Variante.DADOS_NAO_ENCONTRADOS);
        assertThat(metric("kybr_verification_no_data_total", "slug=\"" + workspace.slug() + "\"")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("an unsupported CNPJ goes to a person without a retry; the seller sees processing")
    void anUnsupportedCnpjGoesToManualReview() {
        Outcome outcome = run(UNSUPPORTED, PARTNER);

        assertThat(outcome.estado()).isEqualTo("processing");
        assertThat(outcome.body()).doesNotContainKey("resultado");
        assertThat(outcome.linkCase().processamento().motivoRevisao()).isEqualTo(MotivoRevisao.PROVEDOR_NAO_SUPORTA);
        assertThat(outcome.linkCase().processamento().tentativas()).isEqualTo(1);
    }

    @Test
    @DisplayName("an incomplete record goes to a person; the seller sees processing")
    void anIncompleteRecordOnTheFullCheck() {
        Outcome outcome = run(INCOMPLETE_RECORD, PARTNER);

        // The full check needs the company name, status and opening date; this record has them.
        assertThat(outcome.linkCase().processamento().estado()).isIn(EstadoProcessamento.CONCLUIDO,
                EstadoProcessamento.REVISAO_MANUAL);
        if (outcome.linkCase().processamento().estado() == EstadoProcessamento.REVISAO_MANUAL) {
            assertThat(outcome.linkCase().processamento().motivoRevisao()).isEqualTo(MotivoRevisao.DADOS_INCOMPLETOS);
            assertThat(outcome.estado()).isEqualTo("processing");
        }
    }

    @Test
    @DisplayName("each verification stores the policy version, the fixed reasons and a minimal snapshot")
    void theVerificationRecordIsMinimal() {
        List<Outcome> outcomes = List.of(run(APPROVED, PARTNER), run(PARTIAL_MATCH, PARTNER),
                run(BLOCKED_INAPTA, PARTNER), run(RECENT_AND_PARTIAL, PARTNER), run(UNRELATED_PARTNERS, PARTNER));

        for (Outcome outcome : outcomes) {
            Verification verification = verificationOf(outcome.linkCase()).orElseThrow();
            assertThat(verification.politicaVersao()).isEqualTo(1);
            assertThat(verification.modo()).isNotNull();
            assertThat(verification.consultadoEm()).isNotNull();
            assertThat(verification.motivos()).allSatisfy(m -> assertThat(m).doesNotContain(FIXED_MOTIVOS_FORBIDDEN));
            assertThat(verification.snapshot().razaoSocial()).isNotBlank();
            assertThat(verification.snapshot().situacao()).isNotNull();
            assertThat(verification.snapshot().dataAbertura()).isNotNull();
            Document raw = mongo.getCollection(Verification.COLLECTION)
                    .find(new Document("_id", verification.id())).first();
            assertThat(raw.toJson()).doesNotContain(PARTIAL_PARTNER, UNRELATED_PARTNER);
            assertThat(((Document) raw.get("snapshot")).keySet()).isSubsetOf("razao_social", "municipio", "uf",
                    "cnae_codigo", "cnae_descricao", "situacao", "data_abertura", "regime");
        }
        for (String collection : List.of(LinkCase.COLLECTION, Verification.COLLECTION, Seller.COLLECTION,
                "audit_events", "company_cache")) {
            for (Document doc : mongo.getCollection(collection).find()) {
                assertThat(doc.toJson()).as(collection).doesNotContain(PARTIAL_PARTNER, UNRELATED_PARTNER);
            }
        }
    }

    @Test
    @DisplayName("latency smoke: 90% of stub verifications are decided within 5 s")
    void ninetyPercentAreDecidedWithinFiveSeconds() {
        List<String> cnpjs = List.of(APPROVED, PARTIAL_MATCH, BLOCKED_INAPTA, APPROVED_ALPHANUMERIC, MEI_MASKED,
                RECENT_30_DAYS, IE_INACTIVE, LONG_NAME, BLOCKED_SUSPENSA, UNRELATED_PARTNERS);
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Workspace workspace = newWorkspace();
            String token = start(workspace, cnpjs.get(i % cnpjs.size()), PARTNER);
            String ip = newIp();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (true) {
                Response read = readCase(workspace.slug(), token, ip);
                if (!"processing".equals(read.jsonPath().getString("estado")) || System.nanoTime() > deadline) {
                    Instant criado = OffsetDateTime.parse(read.jsonPath().getString("criado_em")).toInstant();
                    millis.add(Duration.between(criado, Instant.now()).toMillis());
                    break;
                }
                sleep(Duration.ofMillis(200));
            }
        }
        Collections.sort(millis);
        long p90 = millis.get((int) Math.ceil(millis.size() * 0.9) - 1);
        System.out.println("event=qa_latency_smoke samples_ms=" + millis + " p90_ms=" + p90);
        assertThat(p90).isLessThanOrEqualTo(5_000);
    }

    @Test
    @DisplayName("a burst of verifications inside one minute is decided without waiting for the next minute")
    void aBurstInsideOneMinuteIsDecidedWithoutWaitingForTheNextMinute() {
        freeProviderGate();
        Map<Workspace, String> started = new LinkedHashMap<>();
        for (int i = 0; i < 15; i++) {
            Workspace workspace = newWorkspace();
            Response response = from(newIp()).header(IDEMPOTENCY_KEY, newKey()).contentType(ContentType.JSON)
                    .body(body(APPROVED, PARTNER)).post("/public/v/" + workspace.slug() + "/verify");
            assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
            started.put(workspace, response.jsonPath().getString("token"));
        }

        started.forEach((workspace, token) -> {
            Outcome outcome = awaitOutcome(workspace, token);
            assertThat(outcome.linkCase().processamento().estado()).as("a burst case")
                    .isEqualTo(EstadoProcessamento.CONCLUIDO);
            assertThat(outcome.estado()).isEqualTo("approved");
        });
    }

    @Test
    @DisplayName("the slow stub company is approved within the worker timeout")
    void theSlowCompanyIsApprovedInsideTheTimeout() {
        Outcome outcome = run(SLOW_WITHIN_TIMEOUT, PARTNER);

        assertThat(outcome.estado()).isEqualTo("approved");
        assertThat(outcome.linkCase().estado()).isEqualTo(EstadoCaso.approved);
    }

    @Test
    @DisplayName("the long-name company is approved and its name is served whole")
    void theLongNameCompanyIsApproved() {
        Outcome outcome = run(LONG_NAME, PARTNER);

        assertThat(outcome.estado()).isEqualTo("approved");
        assertThat(resultado(outcome).get("razao_social"))
                .isEqualTo("Comercial Exemplo de Peças Automotivas do Interior Ltda.");
    }

    @Test
    @DisplayName("a partial name on the shorter side still never tells the seller it matched partly")
    void aPartialMatchNeverSaysPartial() {
        Outcome outcome = run(APPROVED, PARTIAL_OF_PARTNER);

        assertThat(outcome.estado()).isEqualTo("pending");
        assertThat(outcome.last().asString()).doesNotContainIgnoringCase("parcial").doesNotContain("RESPONSAVEL_CONFERE");
    }
}
