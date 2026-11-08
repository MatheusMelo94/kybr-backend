package com.matheusmelo.kybr.sanitizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.upload.TestImages;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The sanitizer's HTTP endpoint: loopback only, bounded input, one decode at a time, a wall-clock budget. */
class SanitizerServerTest {

    private static final byte[] JPEG_OUT = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private SanitizerServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private SanitizerServer start(SanitizerServer.Sanitize sanitize, Duration budget, int maxRequestBytes) {
        server = new SanitizerServer(0, sanitize, budget, maxRequestBytes);
        server.start();
        return server;
    }

    private HttpResponse<byte[]> post(byte[] body) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/sanitize"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private int health() throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void answersTheSanitizedBytesWithTheirTypeAndSize() throws Exception {
        start(original -> new ImageSanitizer.Sanitized(JPEG_OUT, "image/jpeg", 30, 20), Duration.ofSeconds(5), 1024);

        HttpResponse<byte[]> response = post(TestImages.jpeg(8, 8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(JPEG_OUT);
        assertThat(response.headers().firstValue("Content-Type")).contains("image/jpeg");
        assertThat(response.headers().firstValue(SanitizerServer.WIDTH)).contains("30");
        assertThat(response.headers().firstValue(SanitizerServer.HEIGHT)).contains("20");
    }

    @Test
    void listensOnLoopbackOnly() {
        start(original -> null, Duration.ofSeconds(5), 1024);

        assertThat(server.address().getAddress()).isEqualTo(InetAddress.getLoopbackAddress());
    }

    @Test
    void aRefusedPhotoIs415() throws Exception {
        start(original -> {
            throw new ImageSanitizer.SanitizeException(ImageSanitizer.Outcome.UNSUPPORTED, "cmyk");
        }, Duration.ofSeconds(5), 1024);

        assertThat(post(new byte[] {1, 2, 3}).statusCode()).isEqualTo(415);
    }

    @Test
    void aBodyOverTheCapIs413WithoutCallingTheSanitizer() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        start(original -> {
            called.countDown();
            return null;
        }, Duration.ofSeconds(5), 1024);

        assertThat(post(new byte[1025]).statusCode()).isEqualTo(413);
        assertThat(called.getCount()).isEqualTo(1);
    }

    @Test
    void aDecodeOverTheBudgetIs503AndHealthFailsUntilItEnds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        start(original -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // a native decode ignores interrupts; so does this one
            }
            return new ImageSanitizer.Sanitized(JPEG_OUT, "image/jpeg", 1, 1);
        }, Duration.ofMillis(300), 1024);

        assertThat(post(new byte[] {1}).statusCode()).isEqualTo(503);
        assertThat(health()).isEqualTo(503);
        assertThat(post(new byte[] {1}).statusCode()).as("still decoding: one at a time").isEqualTo(503);

        release.countDown();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (health() != 200 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(health()).isEqualTo(200);
    }

    @Test
    void anUnexpectedFailureIs500WithNoDetail() throws Exception {
        start(original -> {
            throw new IllegalStateException("decoder internals");
        }, Duration.ofSeconds(5), 1024);

        HttpResponse<byte[]> response = post(new byte[] {1});

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(new String(response.body())).doesNotContain("decoder internals");
    }

    @Test
    void otherPathsAndMethodsAreRefused() throws Exception {
        start(original -> null, Duration.ofSeconds(5), 1024);

        HttpResponse<Void> get = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/sanitize")).build(), HttpResponse.BodyHandlers.discarding());
        HttpResponse<Void> other = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/other")).build(), HttpResponse.BodyHandlers.discarding());

        assertThat(get.statusCode()).isEqualTo(405);
        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(health()).isEqualTo(200);
    }
}
