package com.matheusmelo.kybr.service.image;

import com.matheusmelo.kybr.service.scan.ScanLinkTls;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The photo sanitizer service in the isolated scanner organisation ({@code kybr.image.mode=remote}),
 * over the scan link's mutual TLS (the same client certificate, CA, host name check and key pin as
 * the malware scanner; a server failing any of them never receives a byte). It streams the scanned
 * original to {@code POST /sanitize} and reads back the re-encoded photo.
 *
 * <p>Fail closed: only a 200 with the original's own content type and a body within
 * {@code maxResponseBytes} is {@link SanitizeResult.Sanitized}. A 415 is the photo's fault
 * ({@link SanitizeResult.Refused}: it couldn't be decoded). Everything else (any other status, another
 * or missing content type, an oversize or empty body, the whole call past {@code timeout}, any
 * connection or TLS failure) is {@link SanitizeResult.Unavailable}.
 */
public class SanitizerClient implements PhotoSanitizer {

    private static final Logger log = LoggerFactory.getLogger(SanitizerClient.class);

    private final HttpClient http;
    private final URI endpoint;
    private final Duration timeout;
    private final int maxResponseBytes;

    public SanitizerClient(ScanLinkTls tls, String host, int port, Duration connectTimeout, Duration timeout,
            int maxResponseBytes) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(tls.context())
                .sslParameters(tls.parameters(host))
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try {
            this.endpoint = new URI("https", null, host, port, "/sanitize", null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("kybr.sanitizer.host is not a host name or address");
        }
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public SanitizeResult sanitize(InputStream original, long size, ImageHeaderCheck.Format format) {
        String contentType = format == ImageHeaderCheck.Format.JPEG ? "image/jpeg" : "image/png";
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> original),
                        size))
                .build();
        CompletableFuture<HttpResponse<SanitizeResult>> call =
                http.sendAsync(request, info -> reply(info, contentType));
        try {
            return call.get(timeout.toMillis(), TimeUnit.MILLISECONDS).body();
        } catch (TimeoutException e) {
            call.cancel(true);
            log.warn("event=sanitizer_unavailable motivo=timeout");
            return new SanitizeResult.Unavailable(true);
        } catch (ExecutionException e) {
            boolean timedOut = e.getCause() instanceof HttpTimeoutException;
            log.warn("event=sanitizer_unavailable error={}", e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
            return new SanitizeResult.Unavailable(timedOut);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            return new SanitizeResult.Unavailable(false);
        }
    }

    private HttpResponse.BodySubscriber<SanitizeResult> reply(HttpResponse.ResponseInfo info, String expectedType) {
        if (info.statusCode() == 415) {
            return HttpResponse.BodySubscribers.replacing(new SanitizeResult.Refused());
        }
        String type = info.headers().firstValue("Content-Type").map(SanitizerClient::mediaType).orElse("");
        long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (info.statusCode() != 200 || !type.equals(expectedType) || declared > maxResponseBytes) {
            log.warn("event=sanitizer_unexpected_reply status={}", info.statusCode());
            return HttpResponse.BodySubscribers.replacing(new SanitizeResult.Unavailable(false));
        }
        return new BoundedPhoto(maxResponseBytes, expectedType);
    }

    private static String mediaType(String header) {
        return header.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    /** Collects at most {@code limit} bytes; more, or none, is {@link SanitizeResult.Unavailable}. */
    private static final class BoundedPhoto implements HttpResponse.BodySubscriber<SanitizeResult> {

        private final int limit;
        private final String contentType;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<SanitizeResult> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        BoundedPhoto(int limit, String contentType) {
            this.limit = limit;
            this.contentType = contentType;
        }

        @Override
        public CompletionStage<SanitizeResult> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > limit) {
                    log.warn("event=sanitizer_reply_too_large limit={}", limit);
                    result.complete(new SanitizeResult.Unavailable(false));
                    subscription.cancel();
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.size() == 0 ? new SanitizeResult.Unavailable(false)
                    : new SanitizeResult.Sanitized(bytes.toByteArray(), contentType));
        }
    }
}
