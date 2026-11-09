package com.matheusmelo.kybr.service.scan;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code clamd} client for the isolated scanner ({@code kybr.scanner.mode=clamd}), over the
 * scan link's mutual TLS ({@link ScanLinkTls}). It sends {@code zINSTREAM}, the file in 64 KiB
 * length-prefixed chunks read straight from the caller's stream (never the whole file in memory),
 * and a zero-length terminator, then reads one NUL-terminated reply.
 *
 * <p>Fail closed: only the exact reply {@code stream: OK} is clean, and {@code stream: <name> FOUND}
 * is a detection. Anything else (a protocol error such as {@code INSTREAM size limit exceeded}, an
 * empty, truncated or longer reply, other case or spacing), a timeout, a reset and every TLS failure
 * (wrong CA, host name or pinned key) is {@link ScanVerdict.Unavailable}. Each call is counted on
 * {@value #METRIC} by {@code resultado} ({@code clean}, {@code infected}, {@code unavailable},
 * {@code timeout}).
 */
public class ClamdScanner implements MalwareScanner, SignatureMonitor.Source {

    public static final String METRIC = "kybr.scanner.calls";
    static final int CHUNK = 64 * 1024;
    /** Longer than any verdict or version line; a longer reply is not one. */
    static final int MAX_REPLY = 1024;
    private static final Pattern FOUND = Pattern.compile("stream: ([!-~]{1,256}) FOUND");
    private static final DateTimeFormatter VERSION_DATE =
            DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.ENGLISH);
    private static final Logger log = LoggerFactory.getLogger(ClamdScanner.class);

    private final ScanLinkTls tls;
    private final String host;
    private final int port;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final MeterRegistry meters;

    public ClamdScanner(ScanLinkTls tls, String host, int port, Duration connectTimeout, Duration readTimeout,
            MeterRegistry meters) {
        this.tls = tls;
        this.host = host;
        this.port = port;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.meters = meters;
    }

    @Override
    public ScanVerdict scan(InputStream content, long size) {
        String resultado;
        ScanVerdict verdict;
        try (SSLSocket socket = tls.connect(host, port, connectTimeout, readTimeout)) {
            verdict = instream(content, socket.getOutputStream(), socket.getInputStream());
            resultado = verdict.resultado();
        } catch (SocketTimeoutException e) {
            log.warn("event=scanner_unavailable motivo=timeout");
            verdict = new ScanVerdict.Unavailable();
            resultado = "timeout";
        } catch (IOException | RuntimeException e) {
            log.warn("event=scanner_unavailable error={}", e.getClass().getSimpleName());
            verdict = new ScanVerdict.Unavailable();
            resultado = verdict.resultado();
        }
        meters.counter(METRIC, "resultado", resultado).increment();
        return verdict;
    }

    /** {@code zPING}: ready only on an exact {@code PONG}; any failure is "not yet". */
    @Override
    public boolean ping() {
        try (SSLSocket socket = tls.connect(host, port, connectTimeout, readTimeout)) {
            return command("PING", socket.getOutputStream(), socket.getInputStream()).filter("PONG"::equals)
                    .isPresent();
        } catch (IOException | RuntimeException e) {
            log.info("event=scanner_not_ready error={}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * The date of the scanner's newest signatures, from {@code zVERSION}
     * ({@code ClamAV 1.4.1/27450/Thu Nov  5 09:23:45 2026}); the scanner runs in UTC. Empty when the
     * scanner can't be reached or the reply isn't one.
     */
    @Override
    public Optional<Instant> signatureDate() {
        try (SSLSocket socket = tls.connect(host, port, connectTimeout, readTimeout)) {
            return command("VERSION", socket.getOutputStream(), socket.getInputStream())
                    .flatMap(ClamdScanner::parseSignatureDate);
        } catch (IOException | RuntimeException e) {
            log.warn("event=scanner_version_unavailable error={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static Optional<Instant> parseSignatureDate(String version) {
        String[] parts = version.split("/", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(parts[2].strip(), VERSION_DATE).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Streams {@code content} with {@code zINSTREAM} and parses the reply (any transport). */
    static ScanVerdict instream(InputStream content, OutputStream rawOut, InputStream in) throws IOException {
        DataOutputStream out = new DataOutputStream(rawOut);
        out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
        byte[] chunk = new byte[CHUNK];
        int read;
        while ((read = content.readNBytes(chunk, 0, CHUNK)) > 0) {
            out.writeInt(read);
            out.write(chunk, 0, read);
        }
        out.writeInt(0);
        out.flush();
        return reply(in).map(ClamdScanner::verdict).orElseGet(ScanVerdict.Unavailable::new);
    }

    /** Sends a {@code z}-command without a body and reads its reply; empty when it isn't one whole reply. */
    static Optional<String> command(String command, OutputStream out, InputStream in) throws IOException {
        out.write(("z" + command + "\0").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return reply(in);
    }

    /** One NUL-terminated reply followed by the end of the stream; empty for anything else. */
    private static Optional<String> reply(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0) {
            if (reply.size() == MAX_REPLY) {
                return Optional.empty();
            }
            reply.write(b);
        }
        if (b < 0 || in.read() >= 0) {
            return Optional.empty(); // truncated (no NUL), or something after the reply
        }
        return Optional.of(reply.toString(StandardCharsets.US_ASCII));
    }

    static ScanVerdict verdict(String reply) {
        if (reply.equals("stream: OK")) {
            return new ScanVerdict.Clean();
        }
        Matcher found = FOUND.matcher(reply);
        if (found.matches()) {
            return new ScanVerdict.Infected(found.group(1));
        }
        return new ScanVerdict.Unavailable();
    }
}
