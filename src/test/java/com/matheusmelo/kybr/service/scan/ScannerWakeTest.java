package com.matheusmelo.kybr.service.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.MovableClock;
import com.matheusmelo.kybr.support.upload.ScriptedScanner;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** A scan session wakes a stopped scanner by probing it, within at most two minutes. */
class ScannerWakeTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-08T12:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ScriptedScanner scanner = new ScriptedScanner();
    private final ScannerWake wake = new ScannerWake(scanner, Duration.ofSeconds(120), Duration.ofSeconds(5), clock,
            clock::advance, meters);

    @Test
    void anAwakeScannerAnswersTheFirstProbe() {
        assertThat(wake.wake()).isTrue();

        assertThat(scanner.pings()).isEqualTo(1);
        assertThat(timer("ok").count()).isEqualTo(1);
        assertThat(timer("ok").totalTime(TimeUnit.SECONDS)).isZero();
    }

    @Test
    void aStoppedScannerAnsweringOnItsSixthProbeIsAwakeAfterTwentyFiveSeconds() {
        scanner.coldStart(6);

        assertThat(wake.wake()).isTrue();

        assertThat(scanner.pings()).isEqualTo(6);
        assertThat(timer("ok").totalTime(TimeUnit.SECONDS)).isEqualTo(25);
    }

    @Test
    void aScannerThatNeverWakesTimesOutAtTwoMinutes() {
        scanner.neverWakes();
        Instant start = clock.instant();

        assertThat(wake.wake()).isFalse();

        assertThat(Duration.between(start, clock.instant())).isEqualTo(Duration.ofSeconds(120));
        assertThat(scanner.pings()).isEqualTo(25);
        assertThat(timer("timeout").count()).isEqualTo(1);
    }

    private Timer timer(String resultado) {
        return meters.get(ScannerWake.METRIC).tag("resultado", resultado).timer();
    }
}
