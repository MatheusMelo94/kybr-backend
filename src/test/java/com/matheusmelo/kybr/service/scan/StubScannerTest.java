package com.matheusmelo.kybr.service.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.upload.TestFiles;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StubScannerTest {

    private final StubScanner scanner = new StubScanner();

    @Test
    void flagsTheEicarTestString() {
        byte[] eicar = TestFiles.eicar();

        assertThat(scanner.scan(new ByteArrayInputStream(eicar), eicar.length))
                .isEqualTo(new ScanVerdict.Infected(StubScanner.EICAR_SIGNATURE));
    }

    @Test
    void flagsAValidPdfCarryingTheTestMarker() {
        byte[] pdf = TestPdfs.withScannerMarker();

        assertThat(scanner.scan(new ByteArrayInputStream(pdf), pdf.length))
                .isEqualTo(new ScanVerdict.Infected(StubScanner.MARKER_SIGNATURE));
    }

    @Test
    void passesAPlainSyntheticPdf() {
        byte[] pdf = TestPdfs.plain();

        assertThat(scanner.scan(new ByteArrayInputStream(pdf), pdf.length)).isEqualTo(new ScanVerdict.Clean());
    }

    @Test
    void findsAMarkerThatStraddlesTheReadBuffer() {
        byte[] prefix = new byte[StubScanner.CHUNK - 5];
        byte[] marker = StubScanner.TEST_MARKER.getBytes(StandardCharsets.US_ASCII);
        byte[] content = new byte[prefix.length + marker.length + 10];
        System.arraycopy(marker, 0, content, prefix.length, marker.length);

        assertThat(scanner.scan(new ByteArrayInputStream(content), content.length))
                .isInstanceOf(ScanVerdict.Infected.class);
    }

    @Test
    void verdictsNameTheirMetricResult() {
        assertThat(new ScanVerdict.Clean().resultado()).isEqualTo("clean");
        assertThat(new ScanVerdict.Infected("x").resultado()).isEqualTo("infected");
        assertThat(new ScanVerdict.Unavailable().resultado()).isEqualTo("unavailable");
    }
}
