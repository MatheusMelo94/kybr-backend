package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.CreditLedger;
import com.matheusmelo.kybr.repository.CreditLedgerOps;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.service.company.ProviderBudgets.Budget;
import com.matheusmelo.kybr.service.company.ProviderBudgets.Decision;
import com.matheusmelo.kybr.service.ratelimit.RateLimiter;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.MovableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(OutputCaptureExtension.class)
class ProviderBudgetsIT extends MongoIntegrationTest {

    /** 23:30 on 31 October in São Paulo is already 1 November in UTC. */
    private static final Instant OCTOBER_31_LATE_SAO_PAULO = Instant.parse("2026-11-01T02:30:00Z");
    private static final Instant NOVEMBER_10 = Instant.parse("2026-11-10T15:00:00Z");

    @Autowired
    private CreditLedgerOps ledger;
    @Autowired
    private KeyHasher keyHasher;
    @Autowired
    private RateLimiter rateLimiter;
    @Autowired
    private MongoTemplate mongo;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicBoolean spendOn = new AtomicBoolean(true);
    private final String slug = "loja-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void clean() {
        mongo.dropCollection(CreditLedger.COLLECTION);
        mongo.remove(Query.query(Criteria.where("_id").regex("^budget\\.")), "rate_limit_counters");
    }

    @Test
    void theLedgerKeysOnTheSaoPauloMonth() {
        ProviderBudgets budgets = budgets(10_000, OCTOBER_31_LATE_SAO_PAULO);

        budgets.bookD2(slug);

        assertThat(mongo.findById("2026-10", CreditLedger.class)).isNotNull();
        assertThat(mongo.findById("2026-11", CreditLedger.class)).isNull();
    }

    @Test
    void bookingSplitsCreditsByUseAndCallType() {
        ProviderBudgets budgets = budgets(10_000, NOVEMBER_10);

        budgets.bookD2(slug);
        budgets.bookD3(slug);

        CreditLedger month = mongo.findById("2026-11", CreditLedger.class);
        assertThat(month.usados()).isEqualTo(4);
        assertThat(month.d2()).isEqualTo(1);
        assertThat(month.d3()).isEqualTo(3);
        assertThat(month.base()).isEqualTo(2);
        assertThat(month.simples()).isEqualTo(1);
        assertThat(month.ie()).isEqualTo(1);
    }

    @Test
    void monthlyCreditsWarnOnceAt80PercentAndStopSpendingAt100(CapturedOutput output) {
        ProviderBudgets budgets = budgets(10, NOVEMBER_10);

        budgets.bookD3(slug); // 3
        budgets.bookD3(slug); // 6
        assertThat(warnings("monthly")).isZero();
        budgets.bookD2(slug); // 7
        budgets.bookD2(slug); // 8 = 80 %
        budgets.bookD2(slug); // 9
        assertThat(warnings("monthly")).isEqualTo(1);
        assertThat(budgets.canSpend()).isTrue();
        assertThat(meters.get("kybr.provider.credits.used_ratio").gauge().value()).isEqualTo(0.9);

        budgets.bookD2(slug); // 10 = 100 %

        assertThat(budgets.canSpend()).isFalse();
        assertThat(meters.get("kybr.provider.credits.used_ratio").gauge().value()).isEqualTo(1.0);
        assertThat(exhausted("monthly")).isEqualTo(1);
        assertThat(budgets.canSpendD2(slug)).isEqualTo(Decision.exhausted(Budget.MONTHLY));
        assertThat(budgets.canSpendD3(slug)).isEqualTo(Decision.exhausted(Budget.MONTHLY));
        assertThat(output.getAll()).contains("event=budget_warning budget=monthly")
                .contains("event=budget_exhausted budget=monthly");
    }

    @Test
    void lookupsStopAtTheirMonthlyShareWhileVerificationsGoOn() {
        ProviderBudgets budgets = budgets(10_000, NOVEMBER_10);
        ledger.book("2026-11", CreditLedgerOps.Use.D2, 1_999, 0, 0);
        assertThat(budgets.canSpendD2(slug).allowed()).isTrue();

        ledger.book("2026-11", CreditLedgerOps.Use.D2, 1, 0, 0);

        assertThat(budgets.canSpendD2(slug)).isEqualTo(Decision.exhausted(Budget.D2_SHARE));
        assertThat(budgets.canSpendD3(slug).allowed()).isTrue();
    }

    @Test
    void lookupsStopAtTheGlobalDailyBudgetAndResumeTheNextSaoPauloDay() {
        MovableClock clock = new MovableClock(Instant.parse("2026-11-10T02:00:00Z")); // 23:00 on the 9th
        ProviderBudgets budgets = budgets(10_000, clock);
        for (int i = 0; i < 134; i++) {
            budgets.bookD2("slug-" + (i % 10) + "-" + slug);
        }

        assertThat(budgets.canSpendD2(slug)).isEqualTo(Decision.exhausted(Budget.D2_DAILY));
        assertThat(exhausted("d2_daily")).isEqualTo(1);
        assertThat(warnings("d2_daily")).isEqualTo(1);

        clock.set(Instant.parse("2026-11-10T03:00:00Z")); // midnight in São Paulo
        assertThat(budgets.canSpendD2(slug).allowed()).isTrue();
    }

    @Test
    void verificationsStopAtTheGlobalDailyBudget() {
        ProviderBudgets budgets = budgets(10_000, NOVEMBER_10);
        for (int i = 0; i < 177; i++) {
            budgets.bookD3("slug-" + (i % 20) + "-" + slug);
        }
        assertThat(budgets.canSpendD3(slug).allowed()).isTrue(); // 531 credits

        budgets.bookD3("other-" + slug); // 534 credits: the daily budget

        assertThat(budgets.canSpendD3(slug)).isEqualTo(Decision.exhausted(Budget.D3_DAILY));
    }

    @Test
    void aLinksDailyBudgetsOnlyStopThatLink() {
        ProviderBudgets budgets = budgets(10_000, NOVEMBER_10);
        String other = "outra-" + slug;
        for (int i = 0; i < 30; i++) {
            budgets.bookD3(slug);
        }
        for (int i = 0; i < 50; i++) {
            budgets.bookD2(slug);
        }

        assertThat(budgets.canSpendD3(slug)).isEqualTo(Decision.exhausted(Budget.D3_SLUG));
        assertThat(budgets.canSpendD2(slug)).isEqualTo(Decision.exhausted(Budget.D2_SLUG));
        assertThat(budgets.canSpendD3(other).allowed()).isTrue();
        assertThat(budgets.canSpendD2(other).allowed()).isTrue();
        assertThat(warnings("d3_slug")).isEqualTo(1);
        assertThat(exhausted("d3_slug")).isEqualTo(1);
        assertThat(exhausted("d2_slug")).isEqualTo(1);
    }

    @Test
    void aLinksNoDataCapStopsItsVerifications() {
        ProviderBudgets budgets = budgets(10_000, NOVEMBER_10);
        for (int i = 0; i < 20; i++) {
            budgets.recordNoData(slug);
        }

        assertThat(budgets.canSpendD3(slug)).isEqualTo(Decision.exhausted(Budget.NO_DATA));
        assertThat(budgets.canSpendD3("outra-" + slug).allowed()).isTrue();
        assertThat(exhausted("no_data")).isEqualTo(1);
    }

    @Test
    void cacheReadsAboveTheLinksAlertThresholdRaiseOneEventPerDayWithoutBlocking(CapturedOutput output) {
        MovableClock clock = new MovableClock(NOVEMBER_10);
        ProviderBudgets budgets = budgets(10_000, clock);
        for (int i = 0; i < 5; i++) {
            budgets.recordCacheRead(slug);
        }
        clock.advance(Duration.ofDays(1));
        for (int i = 0; i < 3; i++) {
            budgets.recordCacheRead(slug);
        }

        assertThat(output.getAll().split("event=cache_reads_high", -1)).hasSize(3);
        assertThat(budgets.canSpendD2(slug).allowed()).isTrue();
    }

    @Test
    void theSpendOffBrakeStopsLookupsAndVerifications() {
        ProviderBudgets budgets = budgets(10_000, NOVEMBER_10);
        spendOn.set(false);

        assertThat(budgets.canSpendD2(slug)).isEqualTo(Decision.SPEND_OFF);
        assertThat(budgets.canSpendD3(slug)).isEqualTo(Decision.SPEND_OFF);
        assertThat(Decision.SPEND_OFF.allowed()).isFalse();
    }

    @Test
    void concurrentBookingsAreAtomic() throws Exception {
        ProviderBudgets budgets = budgets(10_000, NOVEMBER_10);
        List<Callable<Void>> calls = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            calls.add(() -> {
                budgets.bookD3(slug);
                return null;
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (var future : pool.invokeAll(calls)) {
                future.get();
            }
        }

        CreditLedger month = mongo.findById("2026-11", CreditLedger.class);
        assertThat(month.usados()).isEqualTo(120);
        assertThat(month.d3()).isEqualTo(120);
        assertThat(budgets.canSpendD3(slug)).isEqualTo(Decision.exhausted(Budget.D3_SLUG));
    }

    @Test
    void theProviderRateGateGivesARetryAfterWhenDenied() {
        ProviderBudgets budgets = budgets(10_000, NOVEMBER_10);
        Optional<Duration> denied = Optional.empty();
        for (int i = 0; i < 11 && denied.isEmpty(); i++) {
            denied = budgets.acquireProviderCall();
        }

        assertThat(denied).hasValueSatisfying(retryAfter -> assertThat(retryAfter)
                .isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1)));
    }

    private ProviderBudgets budgets(long monthlyCredits, Instant now) {
        return budgets(monthlyCredits, Clock.fixed(now, ZoneOffset.UTC));
    }

    private ProviderBudgets budgets(long monthlyCredits, Clock clock) {
        ProviderBudgets.Settings settings = new ProviderBudgets.Settings(monthlyCredits, 0.20, 2.0, 50, 30, 20, 3, 1, 1,
                1, 10);
        return new ProviderBudgets(ledger, rateLimiter, keyHasher, spendOn::get, settings, clock, meters);
    }

    private double warnings(String budget) {
        var counter = meters.find("kybr.provider.budget_warning").tag("budget", budget).counter();
        return counter == null ? 0 : counter.count();
    }

    private double exhausted(String budget) {
        var counter = meters.find("kybr.provider.budget_exhausted").tag("budget", budget).counter();
        return counter == null ? 0 : counter.count();
    }
}
