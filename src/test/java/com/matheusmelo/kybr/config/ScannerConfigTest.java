package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.service.scan.ClamdScanner;
import com.matheusmelo.kybr.service.scan.MalwareScanner;
import com.matheusmelo.kybr.service.scan.ScanVerdict;
import com.matheusmelo.kybr.support.tls.FakeClamd;
import com.matheusmelo.kybr.support.tls.TestPki;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The live scanner is built from the scanner settings alone: host, port, timeouts, the pin and the PEMs. */
class ScannerConfigTest {

    private static final TestPki PKI = TestPki.authority("Scan Link Test CA");
    private static final TestPki.Identity SERVER = PKI.issue("scanner", "dns:localhost,ip:127.0.0.1");
    private static final TestPki.Identity CLIENT = PKI.issue("api-client", null);

    @Test
    void clamdModeBuildsAMutualTlsScannerFromTheSettings() throws Exception {
        try (FakeClamd clamd = new FakeClamd(PKI.serverContext(SERVER)).replying("stream: OK\0")) {
            KybrProperties.Scanner settings = new KybrProperties.Scanner(KybrProperties.Scanner.Mode.CLAMD,
                    "localhost", clamd.port(), Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofHours(48),
                    SERVER.spkiSha256(), CLIENT.certificatePem(), CLIENT.keyPem(), PKI.caPem());

            MalwareScanner scanner = ScannerConfig.clamdScanner(ScannerConfig.scanLinkTls(settings), settings,
                    new SimpleMeterRegistry());

            byte[] pdf = TestPdfs.plain();
            assertThat(scanner).isInstanceOf(ClamdScanner.class);
            assertThat(scanner.scan(new ByteArrayInputStream(pdf), pdf.length)).isEqualTo(new ScanVerdict.Clean());
        }
    }
}
