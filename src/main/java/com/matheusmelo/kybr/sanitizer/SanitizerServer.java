package com.matheusmelo.kybr.sanitizer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sanitizer's HTTP endpoint, on the loopback interface only (the scanner machine's TLS front end
 * terminates the scan link's mutual TLS and forwards here).
 *
 * <ul>
 *   <li>{@code POST /sanitize}: the photo's bytes in (at most {@code maxRequestBytes}, else 413);
 *       200 with the re-encoded bytes, their {@code Content-Type} and {@value #WIDTH} /
 *       {@value #HEIGHT}; 415 for a refused photo; 503 when the decode can't finish within the
 *       budget (waiting for the single decode slot included); 500 with no detail otherwise.
 *   <li>{@code GET /health}: 200, or 503 while a decode has run past its budget. A native decode
 *       can't be interrupted, so the platform's health check restarts the process instead.
 * </ul>
 *
 * One decode at a time; a decode past its budget keeps the slot until it ends, so later requests
 * get 503 rather than queueing behind it.
 */
public final class SanitizerServer {

    public static final String WIDTH = "X-Image-Width";
    public static final String HEIGHT = "X-Image-Height";
    private static final Logger log = LoggerFactory.getLogger(SanitizerServer.class);

    /** The work: bytes in, re-encoded photo out. */
    @FunctionalInterface
    public interface Sanitize {
        ImageSanitizer.Sanitized sanitize(byte[] original);
    }

    private final int port;
    private final Sanitize sanitize;
    private final Duration budget;
    private final int maxRequestBytes;
    private final Semaphore slot = new Semaphore(1);
    private volatile long decodeStartedNanos;
    private HttpServer server;
    private ExecutorService decoder;
    private ExecutorService requests;

    public SanitizerServer(int port, Sanitize sanitize, Duration budget, int maxRequestBytes) {
        this.port = port;
        this.sanitize = sanitize;
        this.budget = budget;
        this.maxRequestBytes = maxRequestBytes;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        } catch (IOException e) {
            throw new UncheckedIOException("couldn't open the sanitizer port", e);
        }
        decoder = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("sanitizer-decode").daemon().factory());
        requests = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(requests);
        server.createContext("/sanitize", this::sanitize);
        server.createContext("/health", this::health);
        server.createContext("/", exchange -> respond(exchange, 404, null, null));
        server.start();
        log.info("event=sanitizer_listening port={}", port());
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            decoder.shutdownNow();
            requests.shutdownNow();
            server = null;
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    private void health(HttpExchange exchange) throws IOException {
        long started = decodeStartedNanos;
        boolean overrun = started != 0 && System.nanoTime() - started > budget.toNanos();
        respond(exchange, overrun ? 503 : 200, null, null);
    }

    private void sanitize(HttpExchange exchange) throws IOException {
        if (!"/sanitize".equals(exchange.getRequestURI().getPath())) {
            respond(exchange, 404, null, null);
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, null, null);
            return;
        }
        long deadline = System.nanoTime() + budget.toNanos();
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null && parseLength(declared) > maxRequestBytes) {
            respond(exchange, 413, null, null);
            return;
        }
        byte[] original;
        try (InputStream in = exchange.getRequestBody()) {
            original = in.readNBytes(maxRequestBytes + 1);
        }
        if (original.length > maxRequestBytes) {
            respond(exchange, 413, null, null);
            return;
        }
        try {
            ImageSanitizer.Sanitized result = decodeWithin(original, deadline);
            exchange.getResponseHeaders().set(WIDTH, Integer.toString(result.width()));
            exchange.getResponseHeaders().set(HEIGHT, Integer.toString(result.height()));
            respond(exchange, 200, result.contentType(), result.bytes());
        } catch (ImageSanitizer.SanitizeException e) {
            log.info("event=photo_refused resultado={}", e.outcome());
            respond(exchange, 415, null, null);
        } catch (TimeoutException e) {
            log.warn("event=photo_timeout");
            respond(exchange, 503, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respond(exchange, 503, null, null);
        } catch (RuntimeException e) {
            log.error("event=sanitize_error error={}", e.getClass().getSimpleName());
            respond(exchange, 500, null, null);
        }
    }

    private ImageSanitizer.Sanitized decodeWithin(byte[] original, long deadline)
            throws TimeoutException, InterruptedException {
        if (!slot.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS)) {
            throw new TimeoutException();
        }
        Future<ImageSanitizer.Sanitized> work;
        try {
            work = decoder.submit(() -> {
                decodeStartedNanos = System.nanoTime();
                try {
                    return sanitize.sanitize(original);
                } finally {
                    decodeStartedNanos = 0;
                    slot.release();
                }
            });
        } catch (RuntimeException e) {
            slot.release();
            throw e;
        }
        try {
            return work.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("sanitize failed", e.getCause());
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static long parseLength(String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        try (exchange) {
            if (contentType != null) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if (body == null || body.length == 0) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }
}
