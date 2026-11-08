package com.matheusmelo.kybr.service.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.tls.FakeClamd;
import com.matheusmelo.kybr.support.tls.TestPki;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The clamd client: mutual TLS 1.3 with a pinned server key, streamed chunks, and fail-closed verdicts. */
class ClamdScannerTest {

    private static final TestPki PKI = TestPki.authority("Scan Link Test CA");
    private static final TestPki OTHER_PKI = TestPki.authority("Unrelated Test CA");
    private static final TestPki.Identity SERVER = PKI.issue("scanner", "dns:localhost,ip:127.0.0.1");
    private static final TestPki.Identity CLIENT = PKI.issue("api-client", null);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private FakeClamd clamd;

    @AfterEach
    void stop() throws IOException {
        if (clamd != null) {
            clamd.close();
        }
    }

    private FakeClamd serve(TestPki.Identity identity) throws IOException {
        clamd = new FakeClamd(PKI.serverContext(identity));
        return clamd;
    }

    private ClamdScanner scanner(String host, String pin) {
        return scanner(host, pin, Duration.ofSeconds(5));
    }

    private ClamdScanner scanner(String host, String pin, Duration readTimeout) {
        ScanLinkTls tls = ScanLinkTls.fromPem(CLIENT.certificatePem(), CLIENT.keyPem(), PKI.caPem(), pin);
        return new ClamdScanner(tls, host, clamd.port(), Duration.ofSeconds(3), readTimeout, meters);
    }

    private static ScanVerdict scan(ClamdScanner scanner, byte[] content) {
        return scanner.scan(new ByteArrayInputStream(content), content.length);
    }

    private double calls(String resultado) {
        var counter = meters.find(ClamdScanner.METRIC).tag("resultado", resultado).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void anExactOkIsClean() throws IOException {
        serve(SERVER).replying("stream: OK\0");

        assertThat(scan(scanner("localhost", SERVER.spkiSha256()), TestPdfs.plain())).isEqualTo(new ScanVerdict.Clean());
        assertThat(clamd.served().getFirst().command()).isEqualTo("zINSTREAM");
        assertThat(calls("clean")).isEqualTo(1);
    }

    @Test
    void aFoundReplyIsInfectedWithTheSignatureName() throws IOException {
        serve(SERVER).replying("stream: Kybr.Test.ScannerMarker.UNOFFICIAL FOUND\0");

        assertThat(scan(scanner("localhost", SERVER.spkiSha256()), TestPdfs.withScannerMarker()))
                .isEqualTo(new ScanVerdict.Infected("Kybr.Test.ScannerMarker.UNOFFICIAL"));
        assertThat(calls("infected")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "INSTREAM size limit exceeded. ERROR\0",
        "\0",
        "stream: OK extra\0",
        "stream: ok\0",
        "STREAM: OK\0",
        "stream: OK",
        "stream: O",
        "stream:  FOUND\0",
        "stream: Some Name FOUND\0",
        "stream: Eicar FOUND trailing\0",
        "stream: OK\0stream: OK\0",
        "",
    })
    void anythingButAnExactVerdictIsUnavailable(String reply) throws IOException {
        serve(SERVER).replying(reply);

        assertThat(scan(scanner("localhost", SERVER.spkiSha256()), TestPdfs.plain()))
                .isEqualTo(new ScanVerdict.Unavailable());
        assertThat(calls("unavailable")).isEqualTo(1);
    }

    @Test
    void aSilentScannerTimesOutAsUnavailable() throws IOException {
        serve(SERVER).mode(FakeClamd.Mode.SILENT);

        assertThat(scan(scanner("localhost", SERVER.spkiSha256(), Duration.ofMillis(300)), TestPdfs.plain()))
                .isEqualTo(new ScanVerdict.Unavailable());
        assertThat(calls("timeout")).isEqualTo(1);
    }

    @Test
    void aResetConnectionIsUnavailable() throws IOException {
        serve(SERVER).mode(FakeClamd.Mode.RESET);

        assertThat(scan(scanner("localhost", SERVER.spkiSha256()), TestPdfs.plain()))
                .isEqualTo(new ScanVerdict.Unavailable());
    }

    @Test
    void nothingListeningIsUnavailable() throws IOException {
        serve(SERVER);
        ClamdScanner scanner = scanner("localhost", SERVER.spkiSha256());
        clamd.close();

        assertThat(scan(scanner, TestPdfs.plain())).isEqualTo(new ScanVerdict.Unavailable());
    }

    @Test
    void anIpAddressHostIsCheckedAgainstTheCertificatesIpSan() throws IOException {
        serve(SERVER).replying("stream: OK\0");

        assertThat(scan(scanner("127.0.0.1", SERVER.spkiSha256()), TestPdfs.plain())).isEqualTo(new ScanVerdict.Clean());
    }

    @Test
    void aCertificateFromTheRightCaWithTheWrongSanIsNeverClean() throws IOException {
        TestPki.Identity wrongName = PKI.issue("scanner-wrong-name", "dns:other.test");
        serve(wrongName).replying("stream: OK\0");

        assertThat(scan(scanner("localhost", wrongName.spkiSha256()), TestPdfs.plain()))
                .isEqualTo(new ScanVerdict.Unavailable());
        assertThat(clamd.served()).isEmpty();
    }

    @Test
    void aCertificateWhosePublicKeyDoesNotMatchThePinIsNeverClean() throws IOException {
        TestPki.Identity sameNameOtherKey = PKI.issue("scanner-other-key", "dns:localhost");
        serve(sameNameOtherKey).replying("stream: OK\0");

        assertThat(scan(scanner("localhost", SERVER.spkiSha256()), TestPdfs.plain()))
                .isEqualTo(new ScanVerdict.Unavailable());
        assertThat(clamd.served()).isEmpty();
    }

    @Test
    void aCertificateFromAnotherCaIsNeverClean() throws IOException {
        TestPki.Identity foreign = OTHER_PKI.issue("scanner", "dns:localhost");
        clamd = new FakeClamd(OTHER_PKI.serverContext(foreign)).replying("stream: OK\0");

        assertThat(scan(scanner("localhost", foreign.spkiSha256()), TestPdfs.plain()))
                .isEqualTo(new ScanVerdict.Unavailable());
    }

    @Test
    void streamsTenMebibytesInSixtyFourKibibyteChunksWithoutBufferingTheFile() throws IOException {
        serve(SERVER).replying("stream: OK\0");
        int size = 10 * 1024 * 1024;
        CountingStream content = new CountingStream(new ByteArrayInputStream(new byte[size]));

        assertThat(scanner("localhost", SERVER.spkiSha256()).scan(content, size)).isEqualTo(new ScanVerdict.Clean());

        FakeClamd.Served served = clamd.served().getFirst();
        assertThat(served.bytes()).isEqualTo(size);
        assertThat(served.chunkLengths()).hasSize(160).allMatch(length -> length == 64 * 1024);
        assertThat(content.largestRead()).isLessThanOrEqualTo(64 * 1024);
    }

    @Test
    void sendsALastShortChunk() throws IOException {
        serve(SERVER).replying("stream: OK\0");
        byte[] content = new byte[64 * 1024 + 10];
        Arrays.fill(content, (byte) 'a');

        scan(scanner("localhost", SERVER.spkiSha256()), content);

        assertThat(clamd.served().getFirst().chunkLengths()).containsExactly(64 * 1024, 10);
    }

    @Test
    void aPongMeansTheScannerIsReady() throws IOException {
        serve(SERVER).replying("PONG\0");

        assertThat(scanner("localhost", SERVER.spkiSha256()).ping()).isTrue();
        assertThat(clamd.served().getFirst().command()).isEqualTo("zPING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PONG", "PONG extra\0", "pong\0", ""})
    void anythingButAnExactPongIsNotReady(String reply) throws IOException {
        serve(SERVER).replying(reply);

        assertThat(scanner("localhost", SERVER.spkiSha256()).ping()).isFalse();
    }

    @Test
    void aStoppedOrForeignScannerIsNotReady() throws IOException {
        TestPki.Identity sameNameOtherKey = PKI.issue("scanner-other-key-ping", "dns:localhost");
        serve(sameNameOtherKey).replying("PONG\0");

        assertThat(scanner("localhost", SERVER.spkiSha256()).ping()).isFalse();
        clamd.close();
        assertThat(scanner("localhost", SERVER.spkiSha256()).ping()).isFalse();
    }

    @Test
    void readsTheSignatureDateFromTheVersionReply() throws IOException {
        serve(SERVER).replying("ClamAV 1.4.1/27450/Thu Nov  5 09:23:45 2026\0");

        assertThat(scanner("localhost", SERVER.spkiSha256()).signatureDate())
                .contains(java.time.Instant.parse("2026-11-05T09:23:45Z"));
        assertThat(clamd.served().getFirst().command()).isEqualTo("zVERSION");
    }

    @Test
    void readsATwoDigitDay() throws IOException {
        serve(SERVER).replying("ClamAV 1.4.1/27460/Sun Nov 15 21:00:01 2026\0");

        assertThat(scanner("localhost", SERVER.spkiSha256()).signatureDate())
                .contains(java.time.Instant.parse("2026-11-15T21:00:01Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ClamAV 1.4.1\0", "ClamAV 1.4.1/27450/yesterday\0", "ClamAV 1.4.1/27450/Thu Nov  5", ""})
    void anUnreadableVersionReplyHasNoDate(String reply) throws IOException {
        serve(SERVER).replying(reply);

        assertThat(scanner("localhost", SERVER.spkiSha256()).signatureDate()).isEmpty();
    }

    @Test
    void aWrongServerHasNoDate() throws IOException {
        TestPki.Identity sameNameOtherKey = PKI.issue("scanner-other-key", "dns:localhost");
        serve(sameNameOtherKey).replying("ClamAV 1.4.1/27450/Thu Nov  5 09:23:45 2026\0");

        assertThat(scanner("localhost", SERVER.spkiSha256()).signatureDate()).isEmpty();
    }

    /** Records the largest single read the scanner asks for. */
    private static final class CountingStream extends FilterInputStream {
        private final AtomicInteger largest = new AtomicInteger();

        CountingStream(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            largest.accumulateAndGet(len, Math::max);
            return super.read(b, off, len);
        }

        @Override
        public byte[] readAllBytes() {
            throw new AssertionError("the whole file was buffered");
        }

        @Override
        public byte[] readNBytes(int len) throws IOException {
            largest.accumulateAndGet(len, Math::max);
            return super.readNBytes(len);
        }

        int largestRead() {
            return largest.get();
        }
    }
}
