package com.matheusmelo.kybr.support.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.service.scan.ScanVerdict;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScriptedScannerTest {

    private final ScriptedScanner scanner = new ScriptedScanner();

    @Test
    void unscriptedItAnswersLikeTheStub() {
        assertThat(scan(TestPdfs.plain())).isEqualTo(new ScanVerdict.Clean());
        assertThat(scan(TestPdfs.withScannerMarker())).isInstanceOf(ScanVerdict.Infected.class);
        assertThat(scanner.calls()).isEqualTo(2);
    }

    @Test
    void reportsInfectedOnTheNthFileOnly() {
        scanner.infectedOnCall(2, "Synthetic.Signature");

        assertThat(scan(TestPdfs.plain())).isEqualTo(new ScanVerdict.Clean());
        assertThat(scan(TestPdfs.plain())).isEqualTo(new ScanVerdict.Infected("Synthetic.Signature"));
        assertThat(scan(TestPdfs.plain())).isEqualTo(new ScanVerdict.Clean());
    }

    @Test
    void canBeUnavailable() {
        scanner.unavailable();

        assertThat(scan(TestPdfs.plain())).isEqualTo(new ScanVerdict.Unavailable());
    }

    @Test
    void canBeSlow() {
        scanner.delay(Duration.ofMillis(150));
        long start = System.nanoTime();

        scan(TestPdfs.plain());

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(150));
    }

    @Test
    void aGateHoldsScansUntilReleasedAndTellsWhenOneIsWaiting() throws Exception {
        ScriptedScanner.Gate gate = scanner.holdScans();
        CompletableFuture<ScanVerdict> verdict = CompletableFuture.supplyAsync(() -> scan(TestPdfs.plain()));

        assertThat(gate.awaitEntered(Duration.ofSeconds(5))).isTrue();
        assertThat(verdict).isNotDone();
        gate.release();

        assertThat(verdict.get(5, TimeUnit.SECONDS)).isEqualTo(new ScanVerdict.Clean());
    }

    @Test
    void readsEveryByteAndRemembersTheSizes() {
        byte[] pdf = TestPdfs.plain();
        ByteArrayInputStream in = new ByteArrayInputStream(pdf);

        scanner.scan(in, pdf.length);

        assertThat(in.available()).isZero();
        assertThat(scanner.scannedSizes()).containsExactly((long) pdf.length);
    }

    @Test
    void resetForgetsScriptsAndCalls() {
        scanner.unavailable();
        scan(TestPdfs.plain());

        scanner.reset();

        assertThat(scanner.calls()).isZero();
        assertThat(scan(TestPdfs.plain())).isEqualTo(new ScanVerdict.Clean());
    }

    private ScanVerdict scan(byte[] content) {
        return scanner.scan(new ByteArrayInputStream(content), content.length);
    }

    @Test
    void aColdStartAnswersReadyOnlyFromItsScriptedPingAndCanNeverWake() {
        ScriptedScanner scanner = new ScriptedScanner();
        assertThat(scanner.ping()).isTrue();

        scanner.reset();
        scanner.coldStart(3);
        assertThat(scanner.ping()).isFalse();
        assertThat(scanner.ping()).isFalse();
        assertThat(scanner.ping()).isTrue();

        scanner.reset();
        scanner.neverWakes();
        for (int i = 0; i < 50; i++) {
            assertThat(scanner.ping()).isFalse();
        }
        assertThat(scanner.pings()).isEqualTo(50);
    }
}
