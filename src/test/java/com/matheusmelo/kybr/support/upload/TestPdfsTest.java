package com.matheusmelo.kybr.support.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.service.scan.StubScanner;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The synthetic PDFs are well formed, and each carries exactly the feature it is named for. */
class TestPdfsTest {

    @ParameterizedTest
    @ValueSource(strings = {"plain", "marker", "javascript", "launch", "encrypted"})
    void everyVariantIsAWellFormedPdfWhoseCrossReferencesPointAtItsObjects(String variant) {
        String pdf = text(bytes(variant));

        assertThat(pdf).startsWith("%PDF-1.4\n").endsWith("%%EOF\n");
        Matcher startxref = Pattern.compile("startxref\n(\\d+)\n%%EOF\n$").matcher(pdf);
        assertThat(startxref.find()).isTrue();
        int xref = Integer.parseInt(startxref.group(1));
        assertThat(pdf.substring(xref)).startsWith("xref\n");
        Matcher entry = Pattern.compile("(\\d{10}) 00000 n").matcher(pdf.substring(xref));
        int object = 1;
        while (entry.find()) {
            assertThat(pdf.substring(Integer.parseInt(entry.group(1)))).startsWith(object + " 0 obj");
            object++;
        }
        assertThat(object).isGreaterThan(3);
    }

    @Test
    void onlyTheMarkerVariantCarriesTheScannerMarkerInAnUncompressedStream() {
        assertThat(text(TestPdfs.withScannerMarker())).contains(StubScanner.TEST_MARKER).contains("stream\n")
                .doesNotContain("/Filter");
        assertThat(text(TestPdfs.plain())).doesNotContain("KYBR-SCANNER-SELFTEST");
    }

    @Test
    void activeContentAndEncryptionAppearOnlyWhereAskedFor() {
        assertThat(text(TestPdfs.withJavaScript())).contains("/OpenAction").contains("/JavaScript");
        assertThat(text(TestPdfs.withLaunch())).contains("/OpenAction").contains("/Launch");
        assertThat(text(TestPdfs.encrypted())).containsPattern("trailer\n<<[^>]*/Encrypt 6 0 R");
        assertThat(text(TestPdfs.plain())).doesNotContain("/JavaScript").doesNotContain("/Launch")
                .doesNotContain("/Encrypt").doesNotContain("/OpenAction");
    }

    private static byte[] bytes(String variant) {
        return switch (variant) {
            case "plain" -> TestPdfs.plain();
            case "marker" -> TestPdfs.withScannerMarker();
            case "javascript" -> TestPdfs.withJavaScript();
            case "launch" -> TestPdfs.withLaunch();
            case "encrypted" -> TestPdfs.encrypted();
            default -> throw new IllegalArgumentException(variant);
        };
    }

    private static String text(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }
}
