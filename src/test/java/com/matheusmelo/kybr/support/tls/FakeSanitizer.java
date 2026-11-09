package com.matheusmelo.kybr.support.tls;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * The sanitizer's front end as the API sees it: HTTPS with TLS 1.3 and a required client
 * certificate, {@code POST /sanitize}. It answers a scripted status, content type and body, after
 * an optional delay, and records each request body it received.
 */
public final class FakeSanitizer implements AutoCloseable {

    private final HttpsServer server;
    private final List<byte[]> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String contentType = "image/jpeg";
    private volatile byte[] body = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
    private volatile Duration delay = Duration.ZERO;

    public FakeSanitizer(SSLContext context) {
        try {
            server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters parameters = context.getDefaultSSLParameters();
                parameters.setNeedClientAuth(true);
                parameters.setProtocols(new String[] {"TLSv1.3"});
                params.setSSLParameters(parameters);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/sanitize", exchange -> {
            try (exchange; InputStream in = exchange.getRequestBody()) {
                received.add(in.readAllBytes());
                if (!delay.isZero()) {
                    Thread.sleep(delay);
                }
                if (contentType != null) {
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                }
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // the client went away
            }
        });
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public FakeSanitizer answering(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
        return this;
    }

    public FakeSanitizer delaying(Duration value) {
        delay = value;
        return this;
    }

    /** The request bodies received, in order. */
    public List<byte[]> received() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
