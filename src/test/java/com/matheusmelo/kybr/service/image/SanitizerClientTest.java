package com.matheusmelo.kybr.service.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.service.scan.ScanLinkTls;
import com.matheusmelo.kybr.support.tls.FakeSanitizer;
import com.matheusmelo.kybr.support.tls.TestPki;
import com.matheusmelo.kybr.support.upload.TestImages;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The API's side of the sanitizer: the scan link's mutual TLS, bounded replies, and fail-closed outcomes. */
class SanitizerClientTest {

    private static final TestPki PKI = TestPki.authority("Scan Link Test CA");
    private static final TestPki.Identity SERVER = PKI.issue("scanner", "dns:localhost,ip:127.0.0.1");
    private static final TestPki.Identity CLIENT = PKI.issue("api-client", null);
    private static final byte[] PHOTO = TestImages.jpeg(16, 12);
    private static final byte[] CLEAN_JPEG = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};

    private FakeSanitizer sanitizer;

    @AfterEach
    void stop() {
        if (sanitizer != null) {
            sanitizer.close();
        }
    }

    private FakeSanitizer serve(TestPki.Identity identity) {
        sanitizer = new FakeSanitizer(PKI.serverContext(identity));
        return sanitizer;
    }

    private SanitizerClient client(String pin, Duration timeout, int maxResponse) {
        ScanLinkTls tls = ScanLinkTls.fromPem(CLIENT.certificatePem(), CLIENT.keyPem(), PKI.caPem(), pin);
        return new SanitizerClient(tls, "localhost", sanitizer.port(), Duration.ofSeconds(3), timeout, maxResponse);
    }

    private SanitizerClient client() {
        return client(SERVER.spkiSha256(), Duration.ofSeconds(5), 1024);
    }

    private static SanitizeResult sanitize(SanitizerClient client, ImageHeaderCheck.Format format) {
        return client.sanitize(new ByteArrayInputStream(PHOTO), PHOTO.length, format);
    }

    @Test
    void aJpegAnswerIsTheSanitizedPhoto() {
        serve(SERVER).answering(200, "image/jpeg", CLEAN_JPEG);

        SanitizeResult result = sanitize(client(), ImageHeaderCheck.Format.JPEG);

        assertThat(result).isInstanceOfSatisfying(SanitizeResult.Sanitized.class, sanitized -> {
            assertThat(sanitized.bytes()).isEqualTo(CLEAN_JPEG);
            assertThat(sanitized.contentType()).isEqualTo("image/jpeg");
        });
        assertThat(sanitizer.received()).singleElement().isEqualTo(PHOTO);
    }

    @Test
    void aPngAnswerForAPngIsTheSanitizedPhoto() {
        serve(SERVER).answering(200, "image/png", CLEAN_JPEG);

        assertThat(sanitize(client(), ImageHeaderCheck.Format.PNG)).isInstanceOf(SanitizeResult.Sanitized.class);
    }

    @Test
    void aRefusalIsARefusedPhoto() {
        serve(SERVER).answering(415, null, new byte[0]);

        assertThat(sanitize(client(), ImageHeaderCheck.Format.JPEG)).isInstanceOf(SanitizeResult.Refused.class);
    }

    @Test
    void anyOtherStatusIsUnavailable() {
        for (int status : new int[] {500, 503, 413, 404, 204}) {
            serve(SERVER).answering(status, "image/jpeg", status == 204 ? new byte[0] : CLEAN_JPEG);

            assertThat(sanitize(client(), ImageHeaderCheck.Format.JPEG)).as("status %d", status)
                    .isEqualTo(new SanitizeResult.Unavailable(false));
            sanitizer.close();
        }
        sanitizer = null;
    }

    @Test
    void aWrongContentTypeIsUnavailable() {
        serve(SERVER).answering(200, "text/html", CLEAN_JPEG);
        assertThat(sanitize(client(), ImageHeaderCheck.Format.JPEG)).isEqualTo(new SanitizeResult.Unavailable(false));
    }

    @Test
    void aTypeOtherThanTheInputsIsUnavailable() {
        serve(SERVER).answering(200, "image/png", CLEAN_JPEG);
        assertThat(sanitize(client(), ImageHeaderCheck.Format.JPEG)).isEqualTo(new SanitizeResult.Unavailable(false));
    }

    @Test
    void aMissingContentTypeIsUnavailable() {
        serve(SERVER).answering(200, null, CLEAN_JPEG);
        assertThat(sanitize(client(), ImageHeaderCheck.Format.JPEG)).isEqualTo(new SanitizeResult.Unavailable(false));
    }

    @Test
    void aReplyOverTheCapIsUnavailable() {
        serve(SERVER).answering(200, "image/jpeg", new byte[1025]);
        assertThat(sanitize(client(), ImageHeaderCheck.Format.JPEG)).isEqualTo(new SanitizeResult.Unavailable(false));
    }

    @Test
    void aReplyAtTheCapIsAccepted() {
        serve(SERVER).answering(200, "image/jpeg", new byte[1024]);
        assertThat(sanitize(client(), ImageHeaderCheck.Format.JPEG)).isInstanceOf(SanitizeResult.Sanitized.class);
    }

    @Test
    void aSlowSanitizerTimesOutAsUnavailable() {
        serve(SERVER).answering(200, "image/jpeg", CLEAN_JPEG).delaying(Duration.ofSeconds(3));

        assertThat(client(SERVER.spkiSha256(), Duration.ofMillis(300), 1024).sanitize(new ByteArrayInputStream(PHOTO),
                PHOTO.length, ImageHeaderCheck.Format.JPEG)).isEqualTo(new SanitizeResult.Unavailable(true));
    }

    @Test
    void aCertificateWithTheWrongSanGetsNoPhotoByte() {
        TestPki.Identity wrongName = PKI.issue("scanner-wrong-name", "dns:other.test");
        serve(wrongName);

        assertThat(client(wrongName.spkiSha256(), Duration.ofSeconds(5), 1024)
                .sanitize(new ByteArrayInputStream(PHOTO), PHOTO.length, ImageHeaderCheck.Format.JPEG))
                .isEqualTo(new SanitizeResult.Unavailable(false));
        assertThat(sanitizer.received()).isEmpty();
    }

    @Test
    void aCertificateWhosePublicKeyDoesNotMatchThePinGetsNoPhotoByte() {
        serve(PKI.issue("scanner-other-key", "dns:localhost"));

        assertThat(sanitize(client(), ImageHeaderCheck.Format.JPEG)).isEqualTo(new SanitizeResult.Unavailable(false));
        assertThat(sanitizer.received()).isEmpty();
    }

    @Test
    void nothingListeningIsUnavailable() {
        serve(SERVER);
        SanitizerClient client = client();
        sanitizer.close();
        sanitizer = null;

        assertThat(client.sanitize(new ByteArrayInputStream(PHOTO), PHOTO.length, ImageHeaderCheck.Format.JPEG))
                .isEqualTo(new SanitizeResult.Unavailable(false));
    }
}
