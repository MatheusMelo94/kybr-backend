package com.matheusmelo.kybr.service.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.MovableClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A scan session checks the signatures once: too old or unreadable fails (fail closed); nothing polls. */
class SignatureMonitorTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofHours(48);

    private final MovableClock clock = new MovableClock(NOW);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicReference<Optional<Instant>> signatureDate = new AtomicReference<>(Optional.empty());
    private final SignatureMonitor.Source source = signatureDate::get;

    private SignatureMonitor monitor() {
        return new SignatureMonitor(source, MAX_AGE, clock, meters);
    }

    @Test
    void freshSignaturesPassTheSessionCheck() {
        signatureDate.set(Optional.of(NOW.minus(Duration.ofHours(3))));

        assertThat(monitor().check()).isTrue();
    }

    @Test
    void signaturesOlderThanTheMaximumAgeFailTheCheckUntilTheyAreFreshAgain() {
        signatureDate.set(Optional.of(NOW.minus(Duration.ofHours(47))));
        SignatureMonitor monitor = monitor();
        assertThat(monitor.check()).isTrue();

        clock.advance(Duration.ofHours(1).plusSeconds(1)); // now 48 h and 1 s old

        assertThat(monitor.check()).isFalse();

        signatureDate.set(Optional.of(clock.instant().minus(Duration.ofHours(1))));
        assertThat(monitor.check()).isTrue();
    }

    @Test
    void anUnreadableDateFailsTheCheck() {
        assertThat(monitor().check()).isFalse();
    }

    @Test
    void aSourceThatThrowsFailsTheCheck() {
        SignatureMonitor monitor = new SignatureMonitor(() -> {
            throw new IllegalStateException("boom");
        }, MAX_AGE, clock, meters);

        assertThat(monitor.check()).isFalse();
    }

    @Test
    void nothingReadsTheScannerUntilASessionChecks() {
        AtomicInteger reads = new AtomicInteger();
        SignatureMonitor monitor = new SignatureMonitor(() -> {
            reads.incrementAndGet();
            return Optional.of(clock.instant());
        }, MAX_AGE, clock, meters);

        clock.advance(Duration.ofHours(1)); // an idle hour
        assertThat(reads).hasValue(0);

        monitor.check();
        assertThat(reads).hasValue(1);
    }

    @Test
    void theGaugeShowsTheLastSessionsSignatureAgeInHours() {
        signatureDate.set(Optional.of(NOW.minus(Duration.ofHours(30))));
        SignatureMonitor monitor = monitor();
        monitor.check();

        Gauge gauge = meters.find(SignatureMonitor.GAUGE).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(30.0);

        clock.advance(Duration.ofMinutes(90));
        assertThat(gauge.value()).isEqualTo(31.5);
    }

    @Test
    void theGaugeIsNotANumberBeforeAnyDateWasRead() {
        monitor().check();

        assertThat(meters.find(SignatureMonitor.GAUGE).gauge().value()).isNaN();
    }

    @Test
    void withoutALiveScannerEveryCheckPasses() {
        assertThat(SignatureMonitor.withoutScanner().check()).isTrue();
    }
}
