package com.matheusmelo.kybr.service.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.upload.TestPdfs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Opt-in: the clamd protocol and verdict parsing against a real ClamAV, with the shipped scanner
 * configuration and custom signatures. Enable with {@code -Dkybr.it.clamav=true}, and point
 * {@code -Dkybr.it.clamav.config-dir} at the directory holding {@code clamd.conf} and the custom
 * signature files ({@code *.ndb}, {@code *.ldb}, {@code *.yar}); the container downloads the public
 * signatures at start. The link's TLS is proven by {@link ClamdScannerTest}; this talks to clamd
 * directly. Each detected test file is logged with its signature name, for the deploy evidence.
 */
@EnabledIfSystemProperty(named = "kybr.it.clamav", matches = "true")
class ClamdScannerClamavIT {

    private static final Logger log = LoggerFactory.getLogger(ClamdScannerClamavIT.class);
    private static GenericContainer<?> clamav;

    @BeforeAll
    static void start() throws IOException {
        clamav = new GenericContainer<>(System.getProperty("kybr.it.clamav.image", "clamav/clamav:stable"))
                .withExposedPorts(3310)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(10)));
        String configDir = System.getProperty("kybr.it.clamav.config-dir");
        if (configDir != null) {
            Path dir = Path.of(configDir);
            clamav.withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("clamd.conf")),
                    "/etc/clamav/clamd.conf");
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(file -> file.getFileName().toString().matches(".*\\.(ndb|ldb|yar|yara|hdb)"))
                        .forEach(file -> clamav.withCopyFileToContainer(MountableFile.forHostPath(file),
                                "/var/lib/clamav/" + file.getFileName()));
            }
        }
        clamav.start();
    }

    @AfterAll
    static void stop() {
        if (clamav != null) {
            clamav.stop();
        }
    }

    private static ScanVerdict scan(String name, byte[] content) throws IOException {
        try (Socket socket = new Socket(clamav.getHost(), clamav.getMappedPort(3310))) {
            socket.setSoTimeout(20_000);
            ScanVerdict verdict = ClamdScanner.instream(new ByteArrayInputStream(content), socket.getOutputStream(),
                    socket.getInputStream());
            log.info("real scanner: {} -> {}", name, verdict);
            return verdict;
        }
    }

    @Test
    void aPlainSyntheticPdfIsClean() throws IOException {
        assertThat(scan("plain PDF", TestPdfs.plain())).isEqualTo(new ScanVerdict.Clean());
    }

    @Test
    void aValidPdfCarryingTheTestMarkerIsFound() throws IOException {
        assertThat(scan("marker PDF", TestPdfs.withScannerMarker())).isInstanceOf(ScanVerdict.Infected.class);
    }

    @Test
    void aPdfWithJavaScriptIsFound() throws IOException {
        assertThat(scan("JavaScript PDF", TestPdfs.withJavaScript())).isInstanceOf(ScanVerdict.Infected.class);
    }

    @Test
    void aPdfWithLaunchIsFound() throws IOException {
        assertThat(scan("Launch PDF", TestPdfs.withLaunch())).isInstanceOf(ScanVerdict.Infected.class);
    }

    @Test
    void anEncryptedPdfIsFound() throws IOException {
        assertThat(scan("encrypted PDF", TestPdfs.encrypted())).isInstanceOf(ScanVerdict.Infected.class);
    }
}
