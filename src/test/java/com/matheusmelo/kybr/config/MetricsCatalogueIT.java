package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.CompanyCacheEntry;
import com.matheusmelo.kybr.model.link.CreditLedger;
import com.matheusmelo.kybr.model.link.EstadoProcessamento;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.workspace.Plano;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.CreditLedgerOps;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.MetricTags;
import com.matheusmelo.kybr.service.company.ProviderBudgets;
import com.matheusmelo.kybr.service.company.RuntimeFlagsService;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The metrics the alert rules fire on: each one is registered and moves under its trigger, and no
 * metric name or tag value carries a CNPJ, an e-mail or an IP. The registry checks drive every
 * scenario themselves, so each test passes alone and in any order.
 */
class MetricsCatalogueIT extends WebIntegrationTest {

    /** Every application metric an alert rule or dashboard reads. */
    static final List<String> CATALOGUE = List.of(
            "kybr.link.lookup",
            "kybr.link.verify",
            "kybr.ratelimit.exceeded",
            "kybr.client_ip.absent",
            "kybr.verification.no_data",
            "kybr.verification.no_data_cap",
            "kybr.provider.calls",
            "kybr.provider.credits.used_ratio",
            "kybr.provider.budget_exhausted",
            "kybr.provider.budget_warning",
            "kybr.provider.spend_off",
            "kybr.link.autofill_off",
            "kybr.link.lookup.cache_reads",
            "kybr.link.contact",
            "kybr.verification.duration",
            "kybr.verification.deferred",
            "kybr.verification.failed",
            "kybr.idempotency.completion_missing",
            "kybr.admin.auth_failed");

    private static final String FOUND = "12345678000195";
    private static final String OTHER_FOUND = "25873916000187";
    private static final String NOT_FOUND = "71438562000160";
    private static final String UNAVAILABLE = "52000015000153";
    private static final String UNSUPPORTED = "5200A000000100";
    private static final String CLIENT_IP = "X-Kybr-Client-IP";
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final Duration WAIT = Duration.ofSeconds(15);

    private static final Pattern CNPJ = Pattern.compile(
            "\\d{14}|\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}|[0-9A-Z]{12}\\d{2}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+");
    private static final Pattern IP = Pattern.compile(
            "(^|[^0-9.])\\d{1,3}(\\.\\d{1,3}){3}([^0-9.]|$)|[0-9a-fA-F]{0,4}(:[0-9a-fA-F]{0,4}){2,}");

    @Autowired
    private MeterRegistry meters;
    @Autowired
    private WorkspaceRepository workspaces;
    @Autowired
    private LinkCaseRepository cases;
    @Autowired
    private ProviderBudgets budgets;
    @Autowired
    private CreditLedgerOps ledger;
    @Autowired
    private RuntimeFlagsService flags;
    @Autowired
    private MongoTemplate mongo;

    @LocalManagementPort
    private int managementPort;

    @BeforeEach
    void freshBudgetsAndSwitches() {
        resetBudgets();
        flags.setAutofill(true);
        flags.setProviderSpend(true);
    }

    @AfterEach
    void leaveNoSpentBudgetBehind() {
        resetBudgets();
        flags.setAutofill(true);
        flags.setProviderSpend(true);
    }

    @Test
    void lookupOutcomesProviderCallsAndCacheReadsAreCounted() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());
        Workspace disabled = workspaces.save(TestWorkspaces.builder().slug(newSlug()).autofillAtivo(false).build());
        Workspace trial = workspaces.save(TestWorkspaces.builder().slug(newSlug()).plano(Plano.Tipo.teste).build());
        mongo.remove(new Query(), CompanyCacheEntry.class); // so the first lookups reach the provider

        assertMoves(() -> lookups(workspace, "fresh"), () -> lookup(workspace, FOUND));
        assertMoves(() -> providerCalls("basic", "found"), () -> lookup(workspace, OTHER_FOUND));
        assertMoves(() -> lookups(workspace, "hit"), () -> lookup(workspace, FOUND));
        assertMoves(() -> count("kybr.link.lookup.cache_reads", "slug", workspace.slug()),
                () -> lookup(workspace, FOUND));
        assertMoves(() -> lookups(workspace, "not_found"), () -> lookup(workspace, NOT_FOUND));
        assertMoves(() -> lookups(workspace, "invalid"), () -> lookup(workspace, "not-a-cnpj"));
        assertMoves(() -> lookups(disabled, "disabled"), () -> lookup(disabled, FOUND));
        assertMoves(() -> lookups(trial, "cache_only_miss"), () -> lookup(trial, "98765432000198"));
        assertMoves(() -> providerCalls("basic", "error"), () -> lookup(workspace, UNAVAILABLE));
        assertMoves(() -> lookups(workspace, "error"), () -> lookup(workspace, UNAVAILABLE));
    }

    @Test
    void aVerificationCountsItsStartItsProviderCallAndItsDuration() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());

        assertMoves(() -> count("kybr.link.verify", "slug", workspace.slug(), "resultado", "accepted"),
                () -> verifyAndWait(workspace, FOUND));
        assertMoves(() -> providerCalls("full_check", "found"), () -> verifyAndWait(workspace, "11222333000181"));
        assertMoves(this::durations, () -> verifyAndWait(workspace, FOUND));

        String key = UUID.randomUUID().toString();
        String body = "{\"cnpj\":\"" + FOUND + "\",\"responsavel\":\"Maria Exemplo\"}";
        String ip = randomIp();
        assertThat(verify(workspace, key, body, ip).statusCode()).isEqualTo(201);
        // A replay answers before the slug is resolved, so it is never attributed to a link.
        assertMoves(() -> count("kybr.link.verify", "slug", MetricTags.UNKNOWN, "resultado", "replayed"),
                () -> assertThat(verify(workspace, key, body, ip).statusCode()).isEqualTo(201));
        assertMoves(() -> count("kybr.link.verify", "slug", workspace.slug(), "resultado", "rejected"),
                () -> assertThat(verify(workspace, UUID.randomUUID().toString(),
                        "{\"cnpj\":\"11111111111111\",\"responsavel\":\"Maria Exemplo\"}", ip).statusCode())
                        .isEqualTo(422));
        assertMoves(() -> count("kybr.link.verify", "slug", MetricTags.UNKNOWN, "resultado", "rejected"),
                () -> assertThat(verify(workspace, key, body.replace("Maria", "Ana"), ip).statusCode())
                        .isEqualTo(422));
        assertThat(meters.find("kybr.verification.duration").timers()).allSatisfy(timer -> assertThat(
                timer.takeSnapshot().histogramCounts()).extracting(bucket -> bucket.bucket(TimeUnit.SECONDS))
                .as("a histogram with a 5 s bucket, for a real p90 and the 5 s target").contains(5.0));
    }

    @Test
    void noDataTheNoDataCapDeferralsAndFailuresAreCounted() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());
        Workspace capped = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());

        assertMoves(() -> count("kybr.verification.no_data", "slug", workspace.slug()),
                () -> verifyAndWait(workspace, NOT_FOUND));
        assertMoves(() -> count("kybr.verification.failed", "motivo", "PROVEDOR_NAO_SUPORTA"),
                () -> verifyAndWait(workspace, UNSUPPORTED));

        exhaust("no_data", () -> budgets.recordNoData(capped.slug()));
        assertMoves(() -> count("kybr.verification.no_data_cap", "slug", capped.slug()),
                () -> verifyAndWait(capped, FOUND));

        flags.setProviderSpend(false);
        assertThat(gauge("kybr.provider.spend_off")).isEqualTo(1.0);
        assertMoves(() -> count("kybr.verification.deferred", "motivo", "GASTO_DESLIGADO"),
                () -> verifyAndWait(workspace, FOUND));
        flags.setProviderSpend(true);
        assertThat(gauge("kybr.provider.spend_off")).isEqualTo(0.0);

        flags.setAutofill(false);
        assertThat(gauge("kybr.link.autofill_off")).isEqualTo(1.0);
        flags.setAutofill(true);
        assertThat(gauge("kybr.link.autofill_off")).isEqualTo(0.0);
    }

    @Test
    void everyBudgetWarnsAndThenReportsExhaustion() {
        String slug = newSlug();

        exhaust("d2_slug", () -> budgets.bookD2(slug));
        exhaust("d2_daily", () -> budgets.bookD2(newSlug()));
        exhaust("d3_slug", () -> budgets.bookD3(slug));
        Workspace spent = workspaces.save(TestWorkspaces.builder().slug(slug).build());
        mongo.remove(Query.query(Criteria.where("_id").is("98765432000198")), CompanyCacheEntry.class);
        assertMoves(() -> providerCalls("basic", "budget_denied"), () -> lookup(spent, "98765432000198"));
        assertMoves(() -> providerCalls("full_check", "budget_denied"), () -> verifyAndWait(spent, FOUND));
        exhaust("d3_daily", () -> budgets.bookD3(newSlug()));
        exhaust("no_data", () -> budgets.recordNoData(slug));
        // The month's budgets are large: book up to just below each threshold in one go, then let a
        // real booking cross it.
        long monthly = properties.cnpja().monthlyCredits();
        long d2Share = Math.round(monthly * properties.link().budgets().d2Share());
        topUp(CreditLedgerOps.Use.D2, d2Share * 8 / 10 - 1, CreditLedger::d2);
        bookUntilMoved("kybr.provider.budget_warning", "d2_share", () -> budgets.bookD2(newSlug()));
        topUp(CreditLedgerOps.Use.D2, d2Share - 1, CreditLedger::d2);
        bookUntilMoved("kybr.provider.budget_exhausted", "d2_share", () -> budgets.bookD2(newSlug()));
        topUp(CreditLedgerOps.Use.D3, monthly * 8 / 10 - 1, CreditLedger::usados);
        bookUntilMoved("kybr.provider.budget_warning", "monthly", () -> budgets.bookD3(newSlug()));
        topUp(CreditLedgerOps.Use.D3, monthly - 1, CreditLedger::usados);
        bookUntilMoved("kybr.provider.budget_exhausted", "monthly", () -> budgets.bookD3(newSlug()));

        assertThat(gauge("kybr.provider.credits.used_ratio")).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void rateLimitsContactChurnTheIdempotencyGuardAndAdminAuthFailuresAreCounted() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());
        String ip = randomIp();

        assertMoves(() -> sum("kybr.ratelimit.exceeded"), () -> {
            for (int i = 0; i < properties.link().limits().lookupIp().limit() + 1; i++) {
                http().header(CLIENT_IP, ip).queryParam("cnpj", FOUND).get("/public/v/{slug}/lookup", workspace.slug());
            }
        });

        TestCases.Built built = TestCases.builder(workspace).build();
        cases.save(built.linkCase());
        assertMoves(() -> count("kybr.link.contact", "resultado", "changed"), () -> http().header(CLIENT_IP, randomIp())
                .contentType("application/json").body("{\"email\":\"ana@empresa-exemplo.test\"}")
                .put("/public/v/{slug}/cases/{token}/contact", workspace.slug(), built.token()));

        assertMoves(() -> count("kybr.idempotency.completion_missing"), () -> http()
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .body("{\"name\":\"Loja Exemplo\"}").post("/public/v/{slug}/forgetful-test", newSlug()));

        assertMoves(() -> count("kybr.admin.auth_failed"),
                () -> RestAssured.given().port(managementPort).get("/actuator/admin/x"));
    }

    @Test
    void aMissingClientIpCountsAsExpectedOnlyWhereTheRouteExpectsOne() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());
        double expected = count("kybr.client_ip.absent", "esperado", "true");
        double notExpected = count("kybr.client_ip.absent", "esperado", "false");

        http().get("/public/v/{slug}", workspace.slug());

        assertThat(count("kybr.client_ip.absent", "esperado", "false")).isEqualTo(notExpected + 1);
        assertThat(count("kybr.client_ip.absent", "esperado", "true")).isEqualTo(expected);

        http().queryParam("cnpj", FOUND).get("/public/v/{slug}/lookup", workspace.slug());

        assertThat(count("kybr.client_ip.absent", "esperado", "true")).isEqualTo(expected + 1);
        assertThat(count("kybr.client_ip.absent", "esperado", "false")).isEqualTo(notExpected + 1);
    }

    @Test
    void randomSlugsAddNoApplicationTimeSeries() {
        lookupUnknownSlug();
        Set<Meter.Id> before = meterIds();
        Set<String> slugs = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            String slug = newSlug();
            slugs.add(slug);
            http().header(CLIENT_IP, randomIp()).queryParam("cnpj", FOUND).get("/public/v/{slug}/lookup", slug);
        }

        Set<Meter.Id> added = new HashSet<>(meterIds());
        added.removeAll(before);
        assertThat(added).filteredOn(id -> id.getName().startsWith("kybr.") || id.getTag(MetricTags.SLUG) != null)
                .isEmpty();
        assertThat(meters.getMeters()).flatExtracting(meter -> meter.getId().getTags()).extracting(Tag::getValue)
                .doesNotContainAnyElementsOf(slugs);
    }

    @Test
    void everyCatalogueMetricIsRegistered() {
        driveEveryScenario();
        Set<String> registered = meters.getMeters().stream().map(meter -> meter.getId().getName())
                .collect(Collectors.toSet());

        assertThat(registered).containsAll(CATALOGUE);
    }

    @Test
    void noMetricNameOrTagValueCarriesACnpjAnEmailOrAnIp() {
        driveEveryScenario();
        for (Meter meter : meters.getMeters()) {
            Meter.Id id = meter.getId();
            assertThat(id.getName()).doesNotContainPattern(CNPJ).doesNotContainPattern(EMAIL).doesNotContainPattern(IP);
            for (Tag tag : id.getTags()) {
                assertThat(tag.getValue()).as(id.getName() + "{" + tag.getKey() + "}")
                        .doesNotContainPattern(CNPJ).doesNotContainPattern(EMAIL);
                if (!isDatabaseServerAddress(id, tag)) {
                    assertThat(tag.getValue()).as(id.getName() + "{" + tag.getKey() + "}").doesNotContainPattern(IP);
                }
            }
        }
    }

    /** Runs every scenario that registers a catalogue metric, each from fresh budgets and switches. */
    private void driveEveryScenario() {
        List<Runnable> scenarios = List.of(this::lookupOutcomesProviderCallsAndCacheReadsAreCounted,
                this::aVerificationCountsItsStartItsProviderCallAndItsDuration,
                this::noDataTheNoDataCapDeferralsAndFailuresAreCounted,
                this::everyBudgetWarnsAndThenReportsExhaustion,
                this::rateLimitsContactChurnTheIdempotencyGuardAndAdminAuthFailuresAreCounted,
                this::aMissingClientIpCountsAsExpectedOnlyWhereTheRouteExpectsOne);
        for (Runnable scenario : scenarios) {
            freshBudgetsAndSwitches();
            scenario.run();
        }
        leaveNoSpentBudgetBehind();
    }

    /** The driver's own pool and command meters name the database server; that is no client's IP. */
    private static boolean isDatabaseServerAddress(Meter.Id id, Tag tag) {
        return id.getName().startsWith("mongodb.driver.") && tag.getKey().equals("server.address");
    }

    private Response lookup(Workspace workspace, String cnpj) {
        return http().header(CLIENT_IP, randomIp()).queryParam("cnpj", cnpj)
                .get("/public/v/{slug}/lookup", workspace.slug());
    }

    private void lookupUnknownSlug() {
        http().header(CLIENT_IP, randomIp()).queryParam("cnpj", FOUND).get("/public/v/{slug}/lookup", newSlug());
    }

    private Response verify(Workspace workspace, String key, String body, String ip) {
        return http().header("Idempotency-Key", key).header(CLIENT_IP, ip).contentType("application/json")
                .body(body).post("/public/v/{slug}/verify", workspace.slug());
    }

    /** Starts a verification and waits until the worker has written a verdict or sent it to review. */
    private void verifyAndWait(Workspace workspace, String cnpj) {
        Response response = verify(workspace, UUID.randomUUID().toString(),
                "{\"cnpj\":\"" + cnpj + "\",\"responsavel\":\"Maria Exemplo\"}", randomIp());
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        String hash = TestCases.hash(response.jsonPath().getString("token"));
        awaitUntil(() -> {
            LinkCase linkCase = mongo.findOne(Query.query(Criteria.where(LinkCase.TOKEN_HASH).is(hash)), LinkCase.class);
            return linkCase != null && (linkCase.processamento().estado() == EstadoProcessamento.CONCLUIDO
                    || linkCase.processamento().estado() == EstadoProcessamento.REVISAO_MANUAL);
        });
    }

    /** Runs {@code step} until the budget's warning, then its exhaustion, counter moves. */
    private void exhaust(String budget, Runnable step) {
        bookUntilMoved("kybr.provider.budget_warning", budget, step);
        bookUntilMoved("kybr.provider.budget_exhausted", budget, step);
    }

    private void bookUntilMoved(String metric, String budget, Runnable step) {
        double before = count(metric, "budget", budget);
        for (int i = 0; count(metric, "budget", budget) == before; i++) {
            assertThat(i).as("bookings until " + metric + "{" + budget + "}").isLessThan(1_000);
            step.run();
        }
    }

    /** Books this month's credits directly until {@code field} reads {@code target}. */
    private void topUp(CreditLedgerOps.Use use, long target, ToLongFunction<CreditLedger> field) {
        String month = CreditLedger.idFor(YearMonth.now(SAO_PAULO));
        long current = ledger.month(month).map(field::applyAsLong).orElse(0L);
        assertThat(current).as("already booked").isLessThanOrEqualTo(target);
        if (current < target) {
            ledger.book(month, use, target - current, 0, 0);
        }
    }

    private void resetBudgets() {
        mongo.dropCollection(CreditLedger.COLLECTION);
        mongo.remove(Query.query(Criteria.where("_id").regex("^budget\\.")), "rate_limit_counters");
    }

    /** The worker counts after it writes the case, so a metric may move just after the trigger returns. */
    private static void assertMoves(DoubleSupplier metric, Runnable trigger) {
        double before = metric.getAsDouble();
        trigger.run();
        awaitUntil(() -> metric.getAsDouble() > before);
    }

    private double lookups(Workspace workspace, String resultado) {
        return count("kybr.link.lookup", "slug", workspace.slug(), "resultado", resultado);
    }

    private double providerCalls(String tipo, String resultado) {
        return count("kybr.provider.calls", "tipo", tipo, "resultado", resultado);
    }

    private double count(String name, String... tags) {
        Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double sum(String name) {
        return meters.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private double durations() {
        return meters.find("kybr.verification.duration").timers().stream().mapToLong(Timer::count).sum();
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private Set<Meter.Id> meterIds() {
        return meters.getMeters().stream().map(Meter::getId).collect(Collectors.toSet());
    }

    private static String newSlug() {
        return "loja-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "203.0." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    private static void awaitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + WAIT);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
