package com.matheusmelo.kybr.support.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.service.company.BasicCompany;
import com.matheusmelo.kybr.service.company.CompanyDataResult;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Found;
import com.matheusmelo.kybr.service.company.CompanyDataResult.NotFound;
import com.matheusmelo.kybr.service.company.CompanyDataResult.RateLimited;
import com.matheusmelo.kybr.service.company.CompanyDataResult.Unavailable;
import com.matheusmelo.kybr.service.company.CompanyRecord;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The scriptable provider double: sequences per CNPJ, delays and latches, and call counts. */
class FakeCompanyDataProviderTest {

    private static final String CNPJ = "11222333000181";
    private static final String OTHER = "11444777000161";

    private final FakeCompanyDataProvider provider = new FakeCompanyDataProvider();

    @Test
    void aScriptedSequencePlaysInOrderAndItsLastOutcomeRepeats() {
        CompanyRecord record = FakeCompanyDataProvider.activeCompany();
        provider.scriptFullCheck(CNPJ, new Unavailable<>(), new RateLimited<>(Duration.ofSeconds(30)),
                new Found<>(record));

        assertThat(provider.fullCheck(CNPJ)).isInstanceOf(Unavailable.class);
        assertThat(provider.fullCheck(CNPJ)).isEqualTo(new RateLimited<CompanyRecord>(Duration.ofSeconds(30)));
        assertThat(provider.fullCheck(CNPJ)).isEqualTo(new Found<>(record));
        assertThat(provider.fullCheck(CNPJ)).isEqualTo(new Found<>(record));
        assertThat(provider.fullCheckCalls(CNPJ)).isEqualTo(4);
        assertThat(provider.basicCalls(CNPJ)).isZero();
    }

    @Test
    void basicAndFullCheckAreScriptedAndCountedSeparatelyPerCnpj() {
        BasicCompany basic = FakeCompanyDataProvider.basicCompany();
        provider.scriptBasic(CNPJ, new Found<>(basic));

        CompanyDataResult<BasicCompany> result = provider.basic(CNPJ);

        assertThat(result).isEqualTo(new Found<>(basic));
        assertThat(provider.basic(OTHER)).as("unscripted").isInstanceOf(NotFound.class);
        assertThat(provider.basicCalls(CNPJ)).isEqualTo(1);
        assertThat(provider.basicCalls(OTHER)).isEqualTo(1);
        assertThat(provider.fullCheckCalls(CNPJ)).isZero();
    }

    @Test
    void aDelayHoldsEachCall() {
        provider.delay(CNPJ, Duration.ofMillis(200));

        long start = System.nanoTime();
        provider.basic(CNPJ);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    void aLatchHoldsCallsUntilReleasedAndConcurrencyIsMeasured() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        provider.latch(CNPJ, release);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = pool.submit(() -> provider.fullCheck(CNPJ));
            Future<?> second = pool.submit(() -> provider.fullCheck(CNPJ));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (provider.inFlight() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(first.isDone()).isFalse();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        assertThat(provider.maxConcurrentCalls()).isEqualTo(2);
        assertThat(provider.inFlight()).isZero();
    }

    @Test
    void resetForgetsScriptsAndCounts() {
        provider.scriptBasic(CNPJ, new Unavailable<>());
        provider.basic(CNPJ);

        provider.reset();

        assertThat(provider.basicCalls(CNPJ)).isZero();
        assertThat(provider.basic(CNPJ)).isInstanceOf(NotFound.class);
    }
}
