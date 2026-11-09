package com.matheusmelo.kybr.service.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.tls.TestPki;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The API's photo path (header check, stand-in, sanitizer client) loads no decoder and no sanitizer class. */
class PhotoPathClassLoadTest {

    private static final TestPki PKI = TestPki.authority("Scan Link Test CA");

    @Test
    void theApiPhotoPathLoadsNoImageDecoderOrSanitizerClass(@TempDir Path dir) throws Exception {
        TestPki.Identity client = PKI.issue("api-client", null);
        TestPki.Identity server = PKI.issue("scanner", "dns:localhost,ip:127.0.0.1");
        Path cert = Files.writeString(dir.resolve("client.crt"), client.certificatePem());
        Path key = Files.writeString(dir.resolve("client.key"), client.keyPem());
        Path ca = Files.writeString(dir.resolve("ca.crt"), PKI.caPem());
        Path pin = Files.writeString(dir.resolve("pin"), server.spkiSha256());
        String target = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getParent()
                .toString();
        String classpath = String.join(File.pathSeparator, System.getProperty("java.class.path"),
                target + File.separator + "classes", target + File.separator + "test-classes");

        Process process = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx96m", "-Xshare:off",
                "-verbose:class", "-cp", classpath, PhotoPathProbe.class.getName(),
                cert.toString(), key.toString(), ca.toString(), pin.toString()))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

        assertThat(output).contains(PhotoPathProbe.DONE).contains(SanitizerClient.class.getName())
                .contains(MetadataStrippingSanitizer.class.getName());
        assertThat(output).doesNotContain("javax.imageio").doesNotContain("java.awt.image").doesNotContain("sun.awt")
                .doesNotContain("com.matheusmelo.kybr.sanitizer.");
    }
}
