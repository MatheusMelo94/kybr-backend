package com.matheusmelo.kybr.acceptance.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.matheusmelo.kybr.acceptance.link.fixtures.Cnpjs;
import com.matheusmelo.kybr.acceptance.link.fixtures.ShiftedClock;
import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.CreditLedger;
import com.matheusmelo.kybr.model.link.EstadoProcessamento;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.MotivoRevisao;
import com.matheusmelo.kybr.model.link.Regime;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.company.BasicCompany;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Found;
import com.matheusmelo.kybr.service.company.CompanyDataResult.NotFound;
import com.matheusmelo.kybr.service.company.CompanyDataResult.RateLimited;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unavailable;
import com.matheusmelo.kybr.service.company.CompanyRecord;
import com.matheusmelo.kybr.service.company.ProviderBudgets;
import com.matheusmelo.kybr.service.company.RuntimeFlagsService;
import com.matheusmelo.kybr.support.link.FakeCompanyDataProvider;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Flows the static stub can't express, on a scripted provider: retries and backoff, provider rate
 * limits, every budget and the spend brake, queue overflow recovered by the poller, cache lifetimes,
 * the idempotency window and the São Paulo day boundary (on a shifted application clock).
 *
 * <p>Runs its own context with the recovery poller on, closed after the class, so its poller never
 * claims another test's cases.
 */
@TestPropertySource(properties = {
    "kybr.verification.poller-enabled=true",
    "kybr.verification.poll-interval=200ms",
    "kybr.verification.backoff=100ms,200ms,300ms",
    "kybr.verification.workers=2",
    "kybr.verification.queue=1",
    "kybr.link.budgets.lookup-per-slug-daily=2",
    "kybr.link.budgets.verify-per-slug-daily=6",
    "kybr.link.budgets.no-data-per-slug-daily=2",
    "kybr.link.limits.verify-ip-unknown.limit=3",
    "kybr.link.limits.case-ip-unknown.limit=3"
})
@Import(ScriptedProviderAcceptanceIT.ScriptedProvider.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScriptedProviderAcceptanceIT extends LinkAcceptanceTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final String NAME = "Maria Exemplo";

    /** The scripted provider and the shifted clock as the primary beans of this context. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedProvider {
        @Bean
        @Primary
        FakeCompanyDataProvider scriptedCompanyDataProvider() {
            return new FakeCompanyDataProvider();
        }

        @Bean
        @Primary
        ShiftedClock shiftedClock() {
            return new ShiftedClock();
        }
    }

    @Autowired
    private FakeCompanyDataProvider provider;
    @Autowired
    private ShiftedClock clock;
    @Autowired
    private RuntimeFlagsService flags;
    @Autowired
    private ProviderBudgets budgets;
    @Autowired
    private Clock injectedClock;
    @Autowired
    private KybrProperties properties;

    @BeforeEach
    void settleEveryEarlierCase() {
        provider.reset();
        clock.reset();
        // The poller claims any due case in the shared database; earlier tests' leftovers go to a person.
        mongo.updateMulti(query(where(LinkCase.PROCESSAMENTO_ESTADO).in(EstadoProcessamento.PENDENTE.name(),
                        EstadoProcessamento.EM_ANDAMENTO.name())),
                new Update().set(LinkCase.PROCESSAMENTO_ESTADO, EstadoProcessamento.REVISAO_MANUAL.name()),
                LinkCase.class);
    }

    @AfterEach
    void restore() {
        clock.reset();
        flags.setProviderSpend(true);
        flags.setAutofill(true);
    }

    private static CompanyRecord company(LocalDate opened) {
        return new CompanyRecord("Loja Roteirizada Exemplo Ltda", "Sao Paulo", "SP", "4789099",
                "Comercio varejista de artigos", SituacaoCadastral.ativa, opened, Regime.OUTRO,
                List.of(new CompanyRecord.InscricaoEstadual("SP", true)), List.of(NAME));
    }

    private static CompanyRecord company() {
        return company(LocalDate.of(2015, 3, 2));
    }

    private static BasicCompany basic() {
        return FakeCompanyDataProvider.basicCompany();
    }

    private Outcome settle(Workspace workspace, String token) {
        return awaitUntil(workspace, token, c -> c != null && SETTLED.contains(c.processamento().estado()),
                Duration.ofSeconds(30));
    }

    private void spendTheMonth(long usados) {
        mongo.save(new CreditLedger(CreditLedger.idFor(YearMonth.now(clock.withZone(SAO_PAULO))), usados, 0, usados,
                usados, 0, 0));
    }

    @Test
    void theContextUsesTheScriptedDoubles() {
        assertThat(injectedClock).isSameAs(clock);
    }

    @Test
    @DisplayName("a provider that stays unavailable is tried three times with backoff, then goes to a person")
    void anUnavailableProviderEndsInManualReview() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        provider.scriptFullCheck(cnpj, new Unavailable<>());
        double failed = metric("kybr_verification_failed_total", "motivo=\"PROVEDOR_INDISPONIVEL\"");

        Outcome outcome = settle(workspace, start(workspace, cnpj, NAME));

        assertThat(outcome.linkCase().processamento().estado()).isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
        assertThat(outcome.linkCase().processamento().motivoRevisao()).isEqualTo(MotivoRevisao.PROVEDOR_INDISPONIVEL);
        assertThat(outcome.linkCase().processamento().tentativas()).isEqualTo(3);
        assertThat(provider.fullCheckCalls(cnpj)).isEqualTo(3);
        assertThat(outcome.reads()).allSatisfy(read -> assertThat(json(read)).containsOnlyKeys("estado", "criado_em")
                .containsEntry("estado", "processing"));
        assertThat(metric("kybr_verification_failed_total", "motivo=\"PROVEDOR_INDISPONIVEL\"")).isEqualTo(failed + 1);
    }

    @Test
    @DisplayName("a future opening date goes to a person as incomplete data; the seller sees processing")
    void aFutureOpeningDateGoesToManualReview() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        provider.scriptFullCheck(cnpj, new Found<>(company(LocalDate.now(SAO_PAULO).plusDays(30))));

        Outcome outcome = settle(workspace, start(workspace, cnpj, NAME));

        assertThat(outcome.estado()).isEqualTo("processing");
        assertThat(outcome.body()).doesNotContainKey("resultado");
        assertThat(outcome.linkCase().processamento().estado()).isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
        assertThat(outcome.linkCase().processamento().motivoRevisao()).isEqualTo(MotivoRevisao.DADOS_INCOMPLETOS);
    }

    @Test
    @DisplayName("a provider 429 on every attempt ends in manual review after exactly the maximum attempts")
    void repeatedProviderRateLimitsUseTheAttemptCeiling() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        provider.scriptFullCheck(cnpj, new RateLimited<>(Duration.ofMillis(100)));
        double failed = metric("kybr_verification_failed_total", "motivo=\"PROVEDOR_INDISPONIVEL\"");

        Outcome outcome = settle(workspace, start(workspace, cnpj, NAME));

        assertThat(outcome.linkCase().processamento().motivoRevisao()).isEqualTo(MotivoRevisao.PROVEDOR_INDISPONIVEL);
        assertThat(provider.fullCheckCalls(cnpj)).isEqualTo(3);
        assertThat(outcome.estado()).isEqualTo("processing");
        assertThat(metric("kybr_verification_failed_total", "motivo=\"PROVEDOR_INDISPONIVEL\"")).isEqualTo(failed + 1);
    }

    @Test
    @DisplayName("unavailable then found is approved on the second attempt")
    void unavailableThenFoundIsApproved() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        provider.scriptFullCheck(cnpj, new Unavailable<>(), new Found<>(company()));

        Outcome outcome = settle(workspace, start(workspace, cnpj, NAME));

        assertThat(outcome.estado()).isEqualTo("approved");
        assertThat(outcome.linkCase().processamento().tentativas()).isEqualTo(2);
    }

    @Test
    @DisplayName("a slow then failing provider ends in manual review after the maximum attempts")
    void aSlowFailingProviderEndsInManualReview() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        provider.delay(cnpj, Duration.ofMillis(400)).scriptFullCheck(cnpj, new Unavailable<>());

        Outcome outcome = settle(workspace, start(workspace, cnpj, NAME));

        assertThat(outcome.estado()).isEqualTo("processing");
        assertThat(outcome.linkCase().processamento().motivoRevisao()).isEqualTo(MotivoRevisao.PROVEDOR_INDISPONIVEL);
    }

    @Test
    @DisplayName("a provider 429 is retried at its retry-after and then decided")
    void aProviderRateLimitIsRetried() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        provider.scriptFullCheck(cnpj, new RateLimited<>(Duration.ofSeconds(1)), new Found<>(company()));

        Outcome outcome = settle(workspace, start(workspace, cnpj, NAME));

        assertThat(outcome.estado()).isEqualTo("approved");
        assertThat(provider.fullCheckCalls(cnpj)).isEqualTo(2);
    }

    @Test
    @DisplayName("a provider that keeps answering 429 still reaches a person after the maximum attempts")
    void aPersistentProviderRateLimitReachesManualReview() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        provider.scriptFullCheck(cnpj, new RateLimited<>(Duration.ofMillis(200)));
        String token = start(workspace, cnpj, NAME);

        sleep(Duration.ofSeconds(8));
        LinkCase stored = caseOf(token);

        assertThat(provider.fullCheckCalls(cnpj)).as("provider calls").isGreaterThanOrEqualTo(3);
        assertThat(stored.processamento().estado()).as("after " + provider.fullCheckCalls(cnpj) + " provider 429s")
                .isEqualTo(EstadoProcessamento.REVISAO_MANUAL);
    }

    @Test
    @DisplayName("a saturated provider gate reschedules the case, and the poller decides it later")
    void aSaturatedGateReschedules() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        provider.scriptFullCheck(cnpj, new Found<>(company()));
        freeProviderGate();
        int taken = 0;
        while (budgets.acquireProviderCall().isEmpty() && taken < 1_000) {
            taken++;
        }

        Response created = from(newIp()).header(IDEMPOTENCY_KEY, newKey()).contentType(ContentType.JSON)
                .body(body(cnpj, NAME)).post("/public/v/" + workspace.slug() + "/verify");
        String token = created.jsonPath().getString("token");
        sleep(Duration.ofMillis(500));
        LinkCase rescheduled = caseOf(token);
        long deadline = System.nanoTime() + Duration.ofSeconds(75).toNanos();
        while (!SETTLED.contains(caseOf(token).processamento().estado()) && System.nanoTime() < deadline) {
            sleep(Duration.ofSeconds(1)); // the store only: one client's reads are limited per minute
        }

        assertThat(taken).as("gate slots taken before it denied")
                .isLessThanOrEqualTo(properties.cnpja().ratePerMinute());
        assertThat(rescheduled.processamento().estado()).isEqualTo(EstadoProcessamento.PENDENTE);
        assertThat(rescheduled.processamento().tentativas()).as("a gate denial costs no attempt").isZero();
        assertThat(readCase(workspace.slug(), token, newIp()).jsonPath().getString("estado")).isEqualTo("approved");
    }

    @Test
    @DisplayName("with the month's credits spent a verification is accepted and deferred without a call")
    void aSpentMonthDefersVerification() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        provider.scriptFullCheck(cnpj, new Found<>(company()));
        spendTheMonth(10_000);

        Outcome outcome = settle(workspace, start(workspace, cnpj, NAME));

        assertThat(outcome.estado()).isEqualTo("processing");
        assertThat(outcome.linkCase().processamento().motivoRevisao()).isEqualTo(MotivoRevisao.ORCAMENTO);
        assertThat(provider.fullCheckCalls(cnpj)).isZero();
    }

    @Test
    @DisplayName("the spend brake defers every verification and makes lookups cache-only")
    void theSpendBrake() {
        Workspace workspace = newWorkspace();
        String cached = Cnpjs.unknownToTheStub();
        String uncached = Cnpjs.unknownToTheStub();
        provider.scriptBasic(cached, new Found<>(basic())).scriptBasic(uncached, new Found<>(basic()))
                .scriptFullCheck(cached, new Found<>(company()));
        assertThat(lookup(workspace.slug(), cached, newIp()).statusCode()).isEqualTo(200);

        flags.setProviderSpend(false);
        Outcome outcome = settle(workspace, start(workspace, cached, NAME));

        assertThat(outcome.linkCase().processamento().motivoRevisao()).isEqualTo(MotivoRevisao.GASTO_DESLIGADO);
        assertThat(provider.fullCheckCalls(cached)).isZero();
        assertThat(lookup(workspace.slug(), cached, newIp()).statusCode()).isEqualTo(200);
        assertError(lookup(workspace.slug(), uncached, newIp()), 503, "AUTOFILL_DESATIVADO");
        assertThat(provider.basicCalls(uncached)).isZero();
    }

    @Test
    @DisplayName("after the link's no-data cap, new verifications are deferred without a call; other links are not")
    void theNoDataCap() {
        Workspace workspace = newWorkspace();
        Workspace other = newWorkspace();
        double capped = metric("kybr_verification_no_data_cap_total", "slug=\"" + workspace.slug() + "\"");
        for (int i = 0; i < 2; i++) {
            settle(workspace, start(workspace, Cnpjs.unknownToTheStub(), NAME));
        }
        String found = Cnpjs.unknownToTheStub();
        provider.scriptFullCheck(found, new Found<>(company()));

        Outcome deferred = settle(workspace, start(workspace, found, NAME));
        Outcome elsewhere = settle(other, start(other, found, NAME));

        assertThat(deferred.linkCase().processamento().motivoRevisao()).isEqualTo(MotivoRevisao.LIMITE_SEM_DADOS);
        assertThat(deferred.estado()).isEqualTo("processing");
        assertThat(elsewhere.estado()).isEqualTo("approved");
        assertThat(provider.fullCheckCalls(found)).isEqualTo(1);
        assertThat(metric("kybr_verification_no_data_cap_total", "slug=\"" + workspace.slug() + "\""))
                .isEqualTo(capped + 1);
    }

    @Test
    @DisplayName("after the link's daily verification budget, new verifications are deferred")
    void theDailyPerLinkVerificationBudget() {
        Workspace workspace = newWorkspace();
        List<String> cnpjs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String cnpj = Cnpjs.unknownToTheStub();
            provider.scriptFullCheck(cnpj, new Found<>(company()));
            cnpjs.add(cnpj);
        }
        for (int i = 0; i < 6; i++) {
            assertThat(settle(workspace, start(workspace, cnpjs.get(i), NAME)).estado()).isEqualTo("approved");
        }

        Outcome seventh = settle(workspace, start(workspace, cnpjs.get(6), NAME));

        assertThat(seventh.linkCase().processamento().motivoRevisao()).isEqualTo(MotivoRevisao.ORCAMENTO_DIARIO);
        assertThat(provider.fullCheckCalls(cnpjs.get(6))).isZero();
    }

    @Test
    @DisplayName("submissions that overflow the queue are all verified by the poller")
    void overflowIsRecoveredByThePoller() {
        Workspace workspace = newWorkspace();
        CountDownLatch release = new CountDownLatch(1);
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String cnpj = Cnpjs.unknownToTheStub();
            provider.scriptFullCheck(cnpj, new Found<>(company())).latch(cnpj, release);
            tokens.add(start(workspace, cnpj, NAME));
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (provider.inFlight() < 2 && System.nanoTime() < deadline) {
            sleep(Duration.ofMillis(20));
        }
        int heldAtOnce = provider.inFlight();
        release.countDown();

        for (String token : tokens) {
            assertThat(settle(workspace, token).estado()).isEqualTo("approved");
        }
        assertThat(heldAtOnce).isEqualTo(2);
        assertThat(provider.maxConcurrentCalls()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("after the link's daily lookup budget lookups are cache-only, verification is unaffected, and it resets at the next São Paulo day")
    void theDailyPerLinkLookupBudget() {
        Workspace workspace = newWorkspace();
        List<String> cnpjs = List.of(Cnpjs.unknownToTheStub(), Cnpjs.unknownToTheStub(), Cnpjs.unknownToTheStub());
        cnpjs.forEach(c -> provider.scriptBasic(c, new Found<>(basic())));
        double exhausted = metric("kybr_provider_budget_exhausted_total", "budget=\"d2_slug\"");

        assertThat(lookup(workspace.slug(), cnpjs.get(0), newIp()).statusCode()).isEqualTo(200);
        assertThat(lookup(workspace.slug(), cnpjs.get(1), newIp()).statusCode()).isEqualTo(200);
        assertError(lookup(workspace.slug(), cnpjs.get(2), newIp()), 503, "AUTOFILL_DESATIVADO");

        assertThat(provider.basicCalls(cnpjs.get(2))).isZero();
        assertThat(metric("kybr_provider_budget_exhausted_total", "budget=\"d2_slug\"")).isEqualTo(exhausted + 1);
        assertThat(lookup(workspace.slug(), cnpjs.get(0), newIp()).statusCode()).isEqualTo(200);
        assertThat(verify(workspace.slug(), cnpjs.get(2), NAME, newIp()).statusCode()).isEqualTo(201);
        LocalDate tomorrow = LocalDate.now(clock.withZone(SAO_PAULO)).plusDays(1);
        clock.moveTo(tomorrow.atTime(LocalTime.of(0, 1)).atZone(SAO_PAULO).toInstant());
        assertThat(lookup(workspace.slug(), cnpjs.get(2), newIp()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("with the month spent a miss is cache-only and a hit still answers; 80% warns once")
    void aSpentMonthMakesLookupsCacheOnly() {
        Workspace workspace = newWorkspace();
        String cached = Cnpjs.unknownToTheStub();
        String uncached = Cnpjs.unknownToTheStub();
        provider.scriptBasic(cached, new Found<>(basic())).scriptBasic(uncached, new Found<>(basic()));
        spendTheMonth(7_999);

        try (CapturedEvents events = CapturedEvents.of(ProviderBudgets.class)) {
            assertThat(lookup(workspace.slug(), cached, newIp()).statusCode()).isEqualTo(200);
            List<String> warnings = events.events().stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("event=budget_warning budget=monthly")).toList();
            assertThat(warnings).hasSize(1);
        }
        spendTheMonth(10_000);
        assertThat(lookup(workspace.slug(), cached, newIp()).statusCode()).isEqualTo(200);
        assertError(lookup(workspace.slug(), uncached, newIp()), 503, "AUTOFILL_DESATIVADO");
        assertThat(provider.basicCalls(uncached)).isZero();
    }

    @Test
    @DisplayName("a cached company is fresh for 30 days and a cached miss for 1 day")
    void cacheLifetimes() {
        Workspace workspace = newWorkspace();
        String found = Cnpjs.unknownToTheStub();
        String missing = Cnpjs.unknownToTheStub();
        provider.scriptBasic(found, new Found<>(basic())).scriptBasic(missing, new NotFound<>());

        lookup(workspace.slug(), found, newIp());
        lookup(workspace.slug(), found, newIp());
        lookup(workspace.slug(), missing, newIp());
        lookup(workspace.slug(), missing, newIp());
        assertThat(provider.basicCalls(found)).isEqualTo(1);
        assertThat(provider.basicCalls(missing)).isEqualTo(1);

        clock.shiftBy(Duration.ofDays(1).plusSeconds(1));
        lookup(workspace.slug(), found, newIp());
        assertError(lookup(workspace.slug(), missing, newIp()), 404, "EMPRESA_NAO_ENCONTRADA");
        assertThat(provider.basicCalls(found)).as("still fresh after a day").isEqualTo(1);
        assertThat(provider.basicCalls(missing)).as("a miss is asked again after a day").isEqualTo(2);

        clock.shiftBy(Duration.ofDays(29));
        assertThat(lookup(workspace.slug(), found, newIp()).statusCode()).isEqualTo(200);
        assertThat(provider.basicCalls(found)).as("asked again after 30 days").isEqualTo(2);
    }

    @Test
    @DisplayName("the same key and body after ten minutes starts a new case")
    void theIdempotencyWindowIsTenMinutes() {
        Workspace workspace = newWorkspace();
        String cnpj = Cnpjs.unknownToTheStub();
        String key = newKey();

        Response first = verifyBody(workspace.slug(), body(cnpj, NAME), newIp(), key);
        clock.shiftBy(Duration.ofMinutes(10).plusSeconds(1));
        Response later = verifyBody(workspace.slug(), body(cnpj, NAME), newIp(), key);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(later.statusCode()).isEqualTo(201);
        assertThat(later.jsonPath().getString("token")).isNotEqualTo(first.jsonPath().getString("token"));
        assertThat(casesOf(workspace)).hasSize(2);
    }

    @Test
    @DisplayName("at 23:30 in São Paulo (02:30 UTC the next day) a company's age is counted in São Paulo days")
    void ageIsCountedInSaoPauloDays() {
        LocalDate spToday = LocalDate.now(SAO_PAULO);
        clock.moveTo(spToday.atTime(23, 30).atZone(SAO_PAULO).toInstant());
        Workspace workspace = newWorkspace();
        Map<Integer, String> byAge = Map.of(179, Cnpjs.unknownToTheStub(), 180, Cnpjs.unknownToTheStub(),
                0, Cnpjs.unknownToTheStub());
        byAge.forEach((days, cnpj) -> provider.scriptFullCheck(cnpj, new Found<>(company(spToday.minusDays(days)))));

        Outcome days179 = settle(workspace, start(workspace, byAge.get(179), NAME));
        Outcome days180 = settle(workspace, start(workspace, byAge.get(180), NAME));
        Outcome today = settle(workspace, start(workspace, byAge.get(0), NAME));

        assertThat(days179.last().jsonPath().getList("resultado.itens")).containsExactly(
                Map.of("codigo", "EMPRESA_RECENTE", "dias", 179));
        assertThat(days180.estado()).isEqualTo("approved");
        assertThat(today.last().jsonPath().getList("resultado.itens")).containsExactly(
                Map.of("codigo", "EMPRESA_RECENTE", "dias", 1));
    }

    @Test
    @DisplayName("without a client IP the per-link shared verification limit applies")
    void withoutAClientIpVerificationsShareThePerLinkLimit() {
        Workspace workspace = newWorkspace();
        Workspace other = newWorkspace();
        String path = "/public/v/" + workspace.slug() + "/verify";

        for (int i = 0; i < 3; i++) {
            assertThat(http().header(IDEMPOTENCY_KEY, newKey()).contentType(ContentType.JSON)
                    .body(body(Cnpjs.unknownToTheStub(), NAME)).post(path).statusCode()).isEqualTo(201);
        }
        Response limited = http().header(IDEMPOTENCY_KEY, newKey()).contentType(ContentType.JSON)
                .body(body(Cnpjs.unknownToTheStub(), NAME)).post(path);

        assertError(limited, 429, "LIMITE_TENTATIVAS");
        assertThat(http().header(IDEMPOTENCY_KEY, newKey()).contentType(ContentType.JSON)
                .body(body(Cnpjs.unknownToTheStub(), NAME)).post("/public/v/" + other.slug() + "/verify").statusCode())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("without a client IP the per-link shared case-read limit applies")
    void withoutAClientIpCaseReadsShareThePerLinkLimit() {
        Workspace workspace = newWorkspace();
        String token = start(workspace, Cnpjs.unknownToTheStub(), NAME);
        String path = "/public/v/" + workspace.slug() + "/cases/" + token;

        for (int i = 0; i < 3; i++) {
            assertThat(http().get(path).statusCode()).isEqualTo(200);
        }

        assertError(http().get(path), 429, "LIMITE_TENTATIVAS");
        assertThat(readCase(workspace.slug(), token, newIp()).statusCode()).isEqualTo(200);
    }
}
