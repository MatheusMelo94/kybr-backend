package com.matheusmelo.kybr.service.company;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.RateLimitCounter;
import com.matheusmelo.kybr.model.link.CreditLedger;
import com.matheusmelo.kybr.repository.CreditLedgerOps;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.service.ratelimit.RateLimitDecision;
import com.matheusmelo.kybr.service.ratelimit.RateLimitPolicy;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject;
import com.matheusmelo.kybr.service.ratelimit.RateLimiter;
import com.matheusmelo.kybr.service.ratelimit.SubjectKind;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Provider credit budgets. Every budget degrades service and none fails a request: when one is
 * spent, company lookups go cache-only and verifications defer to manual review.
 *
 * <ul>
 *   <li><b>Monthly credits</b>: a ledger per São Paulo month, split by use (lookups, verifications)
 *       and by call type.
 *   <li><b>Lookup share</b>: lookups may spend at most {@code d2-share} of the month, so
 *       verifications keep the rest.
 *   <li><b>Global daily</b>: {@code ceil(monthly × share ÷ days in month × burst)} for lookups and
 *       the same with {@code 1 − share} for verifications, reset at midnight in São Paulo.
 *   <li><b>Per link daily</b>: fresh lookups, fresh full checks and "no data found" outcomes.
 *   <li><b>Spend off</b>: the operator's brake stops every paid call.
 * </ul>
 *
 * <p>Each budget logs {@code event=budget_warning} and counts {@code kybr.provider.budget_warning}
 * once when a booking crosses 80 %, and {@code budget_exhausted} once when it reaches 100 %.
 * Checks and bookings are separate, so concurrent callers may overshoot a budget by a few calls.
 */
@Service
public class ProviderBudgets {

    private static final Logger log = LoggerFactory.getLogger(ProviderBudgets.class);
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final BigDecimal WARNING_SHARE = new BigDecimal("0.8");
    private static final String COUNTER_PREFIX = "budget.";
    private static final String GLOBAL = "global";
    private static final String CACHE_READS = "cache_reads";

    /** A budget, with its metric tag value. */
    public enum Budget {
        MONTHLY, D2_SHARE, D2_DAILY, D2_SLUG, D3_DAILY, D3_SLUG, NO_DATA;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Whether a paid provider call may happen now.
     *
     * @param exhausted the first spent budget, when one stopped it
     * @param spendOff the operator's brake stopped it
     */
    public record Decision(boolean allowed, Budget exhausted, boolean spendOff) {
        public static final Decision ALLOWED = new Decision(true, null, false);
        public static final Decision SPEND_OFF = new Decision(false, null, true);

        public static Decision exhausted(Budget budget) {
            return new Decision(false, budget, false);
        }
    }

    /** The numbers every budget derives from. */
    record Settings(long monthlyCredits, double d2Share, double burst, int lookupPerSlugDaily,
            int verifyPerSlugDaily, int noDataPerSlugDaily, int cacheReadsPerSlugAlert, int baseCost,
            int simplesCost, int ieCost, int ratePerMinute) {

        static Settings of(KybrProperties properties) {
            KybrProperties.Cnpja cnpja = properties.cnpja();
            KybrProperties.Budgets budgets = properties.link().budgets();
            return new Settings(cnpja.monthlyCredits(), budgets.d2Share(), budgets.burst(),
                    budgets.lookupPerSlugDaily(), budgets.verifyPerSlugDaily(), budgets.noDataPerSlugDaily(),
                    budgets.cacheReadsPerSlugAlert(), cnpja.creditCosts().base(), cnpja.creditCosts().simples(), cnpja.creditCosts().ie(),
                    cnpja.ratePerMinute());
        }

        int fullCheckCost() {
            return baseCost + simplesCost + ieCost;
        }
    }

    private final CreditLedgerOps ledger;
    private final RateLimiter rateLimiter;
    private final KeyHasher keyHasher;
    private final ProviderSpendSwitch spendSwitch;
    private final Settings settings;
    private final Clock clock;
    private final MeterRegistry meters;
    private final RateLimitPolicy providerRate;
    private final AtomicReference<Double> usedRatio = new AtomicReference<>(0.0);

    @Autowired
    public ProviderBudgets(CreditLedgerOps ledger, RateLimiter rateLimiter, KeyHasher keyHasher,
            ProviderSpendSwitch spendSwitch, KybrProperties properties, Clock clock, MeterRegistry meters) {
        this(ledger, rateLimiter, keyHasher, spendSwitch, Settings.of(properties), clock, meters);
    }

    ProviderBudgets(CreditLedgerOps ledger, RateLimiter rateLimiter, KeyHasher keyHasher,
            ProviderSpendSwitch spendSwitch, Settings settings, Clock clock, MeterRegistry meters) {
        this.ledger = ledger;
        this.rateLimiter = rateLimiter;
        this.keyHasher = keyHasher;
        this.spendSwitch = spendSwitch;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
        // The policy's error code is never rendered: a denied provider call reschedules, it never answers a request.
        this.providerRate = new RateLimitPolicy("provider.cnpja", settings.ratePerMinute(), Duration.ofMinutes(1),
                ErrorCode.LIMITE_TENTATIVAS, SubjectKind.SLUG);
        Gauge.builder("kybr.provider.credits.used_ratio", usedRatio, AtomicReference::get).register(meters);
    }

    /** The lookups' global daily budget, in credits. */
    static long d2Daily(long monthly, double share, double burst, YearMonth month) {
        return daily(monthly, BigDecimal.valueOf(share), burst, month);
    }

    /** The verifications' global daily budget, in credits. */
    static long d3Daily(long monthly, double share, double burst, YearMonth month) {
        return daily(monthly, BigDecimal.ONE.subtract(BigDecimal.valueOf(share)), burst, month);
    }

    private static long daily(long monthly, BigDecimal share, double burst, YearMonth month) {
        return BigDecimal.valueOf(monthly).multiply(share).multiply(BigDecimal.valueOf(burst))
                .divide(BigDecimal.valueOf(month.lengthOfMonth()), 0, RoundingMode.CEILING).longValueExact();
    }

    /** False once the month's credits are spent. */
    public boolean canSpend() {
        return monthUsed().usados() < settings.monthlyCredits();
    }

    /** Whether a fresh company lookup for {@code slug} may call the provider. */
    public Decision canSpendD2(String slug) {
        if (!spendSwitch.providerSpendOn()) {
            return Decision.SPEND_OFF;
        }
        CreditLedger month = monthUsed();
        if (month.usados() >= settings.monthlyCredits()) {
            return Decision.exhausted(Budget.MONTHLY);
        }
        if (BigDecimal.valueOf(month.d2()).compareTo(d2Share()) >= 0) {
            return Decision.exhausted(Budget.D2_SHARE);
        }
        Day day = today();
        if (ledger.counter(day.counterId(Budget.D2_DAILY, GLOBAL)) >= limit(Budget.D2_DAILY, day)) {
            return Decision.exhausted(Budget.D2_DAILY);
        }
        if (ledger.counter(day.counterId(Budget.D2_SLUG, slug)) >= settings.lookupPerSlugDaily()) {
            return Decision.exhausted(Budget.D2_SLUG);
        }
        return Decision.ALLOWED;
    }

    /** Whether a verification for {@code slug} may make a fresh full check. */
    public Decision canSpendD3(String slug) {
        if (!spendSwitch.providerSpendOn()) {
            return Decision.SPEND_OFF;
        }
        if (monthUsed().usados() >= settings.monthlyCredits()) {
            return Decision.exhausted(Budget.MONTHLY);
        }
        Day day = today();
        if (ledger.counter(day.counterId(Budget.D3_DAILY, GLOBAL)) >= limit(Budget.D3_DAILY, day)) {
            return Decision.exhausted(Budget.D3_DAILY);
        }
        if (ledger.counter(day.counterId(Budget.D3_SLUG, slug)) >= settings.verifyPerSlugDaily()) {
            return Decision.exhausted(Budget.D3_SLUG);
        }
        if (ledger.counter(day.counterId(Budget.NO_DATA, slug)) >= settings.noDataPerSlugDaily()) {
            return Decision.exhausted(Budget.NO_DATA);
        }
        return Decision.ALLOWED;
    }

    /** Books one fresh company lookup for {@code slug}: the base record's credits. */
    public void bookD2(String slug) {
        long cost = settings.baseCost();
        CreditLedger month = ledger.book(monthId(), CreditLedgerOps.Use.D2, cost, 0, 0);
        crossed(Budget.MONTHLY, month.usados() - cost, month.usados(), BigDecimal.valueOf(settings.monthlyCredits()));
        crossed(Budget.D2_SHARE, month.d2() - cost, month.d2(), d2Share());
        updateRatio(month);
        Day day = today();
        add(day, Budget.D2_DAILY, GLOBAL, cost, limit(Budget.D2_DAILY, day));
        add(day, Budget.D2_SLUG, slug, 1, settings.lookupPerSlugDaily());
    }

    /** Books one fresh full check for {@code slug}: the base record plus the add-ons. */
    public void bookD3(String slug) {
        long cost = settings.fullCheckCost();
        CreditLedger month = ledger.book(monthId(), CreditLedgerOps.Use.D3, settings.baseCost(),
                settings.simplesCost(), settings.ieCost());
        crossed(Budget.MONTHLY, month.usados() - cost, month.usados(), BigDecimal.valueOf(settings.monthlyCredits()));
        updateRatio(month);
        Day day = today();
        add(day, Budget.D3_DAILY, GLOBAL, cost, limit(Budget.D3_DAILY, day));
        add(day, Budget.D3_SLUG, slug, 1, settings.verifyPerSlugDaily());
    }

    /** Counts one "no data found" outcome for {@code slug}. */
    public void recordNoData(String slug) {
        add(today(), Budget.NO_DATA, slug, 1, settings.noDataPerSlugDaily());
    }

    /**
     * Counts one cache read for {@code slug}. Reading the cache never costs credits and is never
     * blocked, but the cache holds individuals' company names, so the read that crosses the link's
     * daily alert threshold logs {@code event=cache_reads_high}, once per day.
     */
    public void recordCacheRead(String slug) {
        Day day = today();
        long after = ledger.addToCounter(day.counterId(CACHE_READS, slug), 1, day.expiry());
        if (after == settings.cacheReadsPerSlugAlert()) {
            log.warn("event=cache_reads_high slug={}", slug);
        }
    }

    /**
     * Takes one slot of the provider's per-minute limit, shared by every machine.
     *
     * @return empty when the call may go ahead; otherwise how long to wait
     */
    public Optional<Duration> acquireProviderCall() {
        RateLimitDecision decision = rateLimiter.consume(providerRate, new RateLimitSubject.Slug("_provider"));
        return decision.allowed() ? Optional.empty() : Optional.of(Duration.ofSeconds(decision.retryAfterSeconds()));
    }

    private CreditLedger monthUsed() {
        CreditLedger month = ledger.month(monthId()).orElse(new CreditLedger(monthId(), 0, 0, 0, 0, 0, 0));
        updateRatio(month);
        return month;
    }

    private void updateRatio(CreditLedger month) {
        usedRatio.set((double) month.usados() / settings.monthlyCredits());
    }

    private BigDecimal d2Share() {
        return BigDecimal.valueOf(settings.monthlyCredits()).multiply(BigDecimal.valueOf(settings.d2Share()));
    }

    private long limit(Budget budget, Day day) {
        YearMonth month = YearMonth.from(day.date());
        return budget == Budget.D2_DAILY
                ? d2Daily(settings.monthlyCredits(), settings.d2Share(), settings.burst(), month)
                : d3Daily(settings.monthlyCredits(), settings.d2Share(), settings.burst(), month);
    }

    private void add(Day day, Budget budget, String subject, long amount, long limit) {
        long after = ledger.addToCounter(day.counterId(budget, subject), amount, day.expiry());
        crossed(budget, after - amount, after, BigDecimal.valueOf(limit));
    }

    /** Emits the 80 % warning and the 100 % exhaustion once each: only the booking that crosses them does. */
    private void crossed(Budget budget, long before, long after, BigDecimal limit) {
        BigDecimal warning = limit.multiply(WARNING_SHARE);
        if (BigDecimal.valueOf(before).compareTo(warning) < 0 && BigDecimal.valueOf(after).compareTo(warning) >= 0) {
            meters.counter("kybr.provider.budget_warning", "budget", budget.tag()).increment();
            log.warn("event=budget_warning budget={}", budget.tag());
        }
        if (BigDecimal.valueOf(before).compareTo(limit) < 0 && BigDecimal.valueOf(after).compareTo(limit) >= 0) {
            meters.counter("kybr.provider.budget_exhausted", "budget", budget.tag()).increment();
            log.warn("event=budget_exhausted budget={}", budget.tag());
        }
    }

    private String monthId() {
        return CreditLedger.idFor(YearMonth.now(clock.withZone(SAO_PAULO)));
    }

    private Day today() {
        return new Day(LocalDate.now(clock.withZone(SAO_PAULO)));
    }

    /** A São Paulo day: the window of every daily budget. */
    private final class Day {
        private final LocalDate date;

        Day(LocalDate date) {
            this.date = date;
        }

        LocalDate date() {
            return date;
        }

        /** Same shape as a rate-limit counter id: name, hashed subject, window start. */
        String counterId(Budget budget, String subject) {
            return counterId(budget.tag(), subject);
        }

        String counterId(String name, String subject) {
            long start = date.atStartOfDay(SAO_PAULO).toEpochSecond();
            return RateLimitCounter.idFor(COUNTER_PREFIX + name, keyHasher.hmac("budget:" + subject), start);
        }

        /** A day after the window ends, so a late reader still sees the count. */
        Instant expiry() {
            return date.plusDays(2).atStartOfDay(SAO_PAULO).toInstant();
        }
    }
}
