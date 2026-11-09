package com.matheusmelo.kybr.service.image;

import com.matheusmelo.kybr.service.scan.ScanLinkTls;
import com.matheusmelo.kybr.support.upload.ImageHeaders;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Runs the API's whole photo path in a fresh JVM, so a test can list the classes it loads
 * ({@code -verbose:class}): the header check, the in-process stand-in, and the sanitizer client
 * (against a closed port). Arguments: files holding the client certificate, key, CA and pin.
 */
public final class PhotoPathProbe {

    static final String DONE = "photo-path-probe-done";

    private PhotoPathProbe() {
    }

    public static void main(String[] args) throws Exception {
        byte[] photo = ImageHeaders.jpeg(400, 300);
        System.out.println(new ImageHeaderCheck(50_000_000, 12_000).check(new ByteArrayInputStream(photo)));
        System.out.println(new MetadataStrippingSanitizer()
                .sanitize(new ByteArrayInputStream(photo), photo.length, ImageHeaderCheck.Format.JPEG).resultado());
        ScanLinkTls tls = ScanLinkTls.fromPem(Files.readString(Path.of(args[0])), Files.readString(Path.of(args[1])),
                Files.readString(Path.of(args[2])), Files.readString(Path.of(args[3])).strip());
        SanitizerClient client = new SanitizerClient(tls, "127.0.0.1", 1, Duration.ofSeconds(1), Duration.ofSeconds(2),
                1024);
        System.out.println(client.sanitize(new ByteArrayInputStream(photo), photo.length,
                ImageHeaderCheck.Format.JPEG).resultado());
        System.out.println(DONE);
    }
}
