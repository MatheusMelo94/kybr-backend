package com.matheusmelo.kybr.service.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The scanner stand-in ({@code kybr.scanner.mode=stub}: local, docker, test and staging by default).
 * It flags exactly what the real scanner's test signatures flag: the EICAR test string and the
 * Kybr scanner self-test marker, which the real scanner carries as a custom signature so a valid
 * PDF can prove the scan path end to end. Everything else is clean, so it never runs in prod (the
 * bean doesn't exist there, and the prod guard refuses the mode).
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "kybr.scanner", name = "mode", havingValue = "stub", matchIfMissing = true)
public class StubScanner implements MalwareScanner {

    /** The self-test marker: an ASCII run no real file contains. */
    public static final String TEST_MARKER = "KYBR-SCANNER-SELFTEST-5E1F7D3C";
    public static final String MARKER_SIGNATURE = "Kybr.Test.ScannerMarker";
    public static final String EICAR_SIGNATURE = "Eicar-Test-Signature";
    static final int CHUNK = 64 * 1024;

    // Built at run time from two halves, so no class file holds the test string whole.
    private static final byte[] EICAR = String.join("", "X5O!P%@AP[4\\PZX54(P^)7CC)7}$",
            "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*").getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MARKER = TEST_MARKER.getBytes(StandardCharsets.US_ASCII);
    private static final int OVERLAP = Math.max(EICAR.length, MARKER.length) - 1;

    @Override
    public ScanVerdict scan(InputStream content, long size) {
        byte[] window = new byte[OVERLAP + CHUNK];
        int carried = 0;
        try {
            int read;
            while ((read = content.readNBytes(window, carried, CHUNK)) > 0) {
                int filled = carried + read;
                if (contains(window, filled, EICAR)) {
                    return new ScanVerdict.Infected(EICAR_SIGNATURE);
                }
                if (contains(window, filled, MARKER)) {
                    return new ScanVerdict.Infected(MARKER_SIGNATURE);
                }
                carried = Math.min(OVERLAP, filled);
                System.arraycopy(window, filled - carried, window, 0, carried);
            }
            return new ScanVerdict.Clean();
        } catch (IOException e) {
            return new ScanVerdict.Unavailable();
        }
    }

    /** Always ready. */
    @Override
    public boolean ping() {
        return true;
    }

    private static boolean contains(byte[] haystack, int length, byte[] needle) {
        for (int i = 0; i + needle.length <= length; i++) {
            if (haystack[i] == needle[0] && Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                return true;
            }
        }
        return false;
    }
}
