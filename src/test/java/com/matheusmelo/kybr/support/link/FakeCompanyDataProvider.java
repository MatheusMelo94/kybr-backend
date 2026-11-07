package com.matheusmelo.kybr.support.link;

import com.matheusmelo.kybr.model.link.Regime;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import com.matheusmelo.kybr.service.company.BasicCompany;
import com.matheusmelo.kybr.service.company.CompanyDataProvider;
import com.matheusmelo.kybr.service.company.CompanyDataResult;
import com.matheusmelo.kybr.service.company.CompanyDataResult.NotFound;
import com.matheusmelo.kybr.service.company.CompanyRecord;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scriptable {@link CompanyDataProvider} for tests.
 *
 * <ul>
 *   <li><b>Scripts:</b> per CNPJ and method, a sequence of outcomes played in order; the last one
 *       repeats. An unscripted CNPJ is {@link NotFound}.
 *   <li><b>Delay or latch:</b> per CNPJ, each call sleeps for the delay and/or waits for the latch
 *       (at most 30 s) before answering.
 *   <li><b>Counters:</b> calls per CNPJ and method, calls in flight, and the most ever in flight.
 * </ul>
 *
 * <p>Make it the primary provider bean with {@link LinkTestDoublesConfiguration}. It's a shared bean
 * in a cached context: use a CNPJ of your own per test, or {@link #reset()}.
 */
public class FakeCompanyDataProvider implements CompanyDataProvider {

    private final Map<String, Script<BasicCompany>> basicScripts = new ConcurrentHashMap<>();
    private final Map<String, Script<CompanyRecord>> fullCheckScripts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> basicCalls = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> fullCheckCalls = new ConcurrentHashMap<>();
    private final Map<String, Duration> delays = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> latches = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();

    /** A complete basic record with synthetic values. */
    public static BasicCompany basicCompany() {
        return new BasicCompany("Loja Exemplo Comercio Ltda", "Sao Paulo", "SP", "Comercio varejista de artigos");
    }

    /** A complete full record with synthetic values: active, opened years ago, one partner. */
    public static CompanyRecord activeCompany() {
        return new CompanyRecord("Loja Exemplo Comercio Ltda", "Sao Paulo", "SP", "4789099",
                "Comercio varejista de artigos", SituacaoCadastral.ativa, LocalDate.of(2015, 3, 2), Regime.OUTRO,
                List.of(new CompanyRecord.InscricaoEstadual("SP", true)), List.of("Maria Exemplo"));
    }

    @SafeVarargs
    public final FakeCompanyDataProvider scriptBasic(String cnpj, CompanyDataResult<BasicCompany>... outcomes) {
        basicScripts.put(cnpj, new Script<>(List.of(outcomes)));
        return this;
    }

    @SafeVarargs
    public final FakeCompanyDataProvider scriptFullCheck(String cnpj, CompanyDataResult<CompanyRecord>... outcomes) {
        fullCheckScripts.put(cnpj, new Script<>(List.of(outcomes)));
        return this;
    }

    public FakeCompanyDataProvider delay(String cnpj, Duration delay) {
        delays.put(cnpj, delay);
        return this;
    }

    public FakeCompanyDataProvider latch(String cnpj, CountDownLatch latch) {
        latches.put(cnpj, latch);
        return this;
    }

    @Override
    public CompanyDataResult<BasicCompany> basic(String cnpj) {
        return call(cnpj, basicCalls, basicScripts);
    }

    @Override
    public CompanyDataResult<CompanyRecord> fullCheck(String cnpj) {
        return call(cnpj, fullCheckCalls, fullCheckScripts);
    }

    public int basicCalls(String cnpj) {
        return count(basicCalls, cnpj);
    }

    public int fullCheckCalls(String cnpj) {
        return count(fullCheckCalls, cnpj);
    }

    /** Calls answering right now. */
    public int inFlight() {
        return inFlight.get();
    }

    /** The most calls ever in flight at once since the last {@link #reset()}. */
    public int maxConcurrentCalls() {
        return maxConcurrent.get();
    }

    public void reset() {
        basicScripts.clear();
        fullCheckScripts.clear();
        basicCalls.clear();
        fullCheckCalls.clear();
        delays.clear();
        latches.clear();
        maxConcurrent.set(inFlight.get());
    }

    private <T> CompanyDataResult<T> call(String cnpj, Map<String, AtomicInteger> counters,
            Map<String, Script<T>> scripts) {
        counters.computeIfAbsent(cnpj, key -> new AtomicInteger()).incrementAndGet();
        maxConcurrent.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            hold(cnpj);
            Script<T> script = scripts.get(cnpj);
            return script == null ? new NotFound<>() : script.next();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void hold(String cnpj) {
        try {
            Duration delay = delays.get(cnpj);
            if (delay != null) {
                Thread.sleep(delay);
            }
            CountDownLatch latch = latches.get(cnpj);
            if (latch != null && !latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while held", e);
        }
    }

    private static int count(Map<String, AtomicInteger> counters, String cnpj) {
        AtomicInteger count = counters.get(cnpj);
        return count == null ? 0 : count.get();
    }

    /** Outcomes played in order; the last repeats. */
    private static final class Script<T> {
        private final List<CompanyDataResult<T>> outcomes;
        private int next;

        Script(List<CompanyDataResult<T>> outcomes) {
            if (outcomes.isEmpty()) {
                throw new IllegalArgumentException("a script needs at least one outcome");
            }
            this.outcomes = new ArrayList<>(outcomes);
        }

        synchronized CompanyDataResult<T> next() {
            CompanyDataResult<T> outcome = outcomes.get(Math.min(next, outcomes.size() - 1));
            next++;
            return outcome;
        }
    }
}
