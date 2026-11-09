package com.matheusmelo.kybr.support.upload;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An S3-compatible endpoint for client tests (path-style): records every request and answers
 * {@code PutObject} 200, {@code DeleteObject} 204 and {@code ListObjectsV2} from its own list of keys.
 * It can be told to fail every request with a status and an error body.
 */
public final class StubS3Server implements AutoCloseable {

    /** One request as received; header names lower-cased. */
    public record Recorded(String method, String path, String query, Map<String, String> headers, long bodyBytes) {

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    /** A body no client may ever log or put in an exception. */
    public static final String SECRET_BODY_MARKER = "STUB-S3-ERROR-BODY-DO-NOT-LOG";
    /** What a GetObject answers. */
    public static final byte[] OBJECT_BYTES = {7, 8, 9};

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private volatile int failWith;

    public StubS3Server() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    /** Keys the listing reports (with a size of 3 and a fixed date). */
    public void holding(String... values) {
        keys.clear();
        keys.addAll(List.of(values));
    }

    /** Every request from now on fails with {@code status} and an error body. */
    public void failWith(int status) {
        failWith = status;
    }

    private void handle(HttpExchange exchange) throws IOException {
        long bytes;
        try (InputStream in = exchange.getRequestBody()) {
            bytes = in.transferTo(OutputStream.nullOutputStream());
        }
        Map<String, String> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), String.join(",", values)));
        URI uri = exchange.getRequestURI();
        requests.add(new Recorded(exchange.getRequestMethod(), uri.getRawPath(), uri.getRawQuery(), headers, bytes));
        if (failWith != 0) {
            respond(exchange, failWith, "<Error><Code>AccessDenied</Code><Message>" + SECRET_BODY_MARKER
                    + "</Message></Error>");
            return;
        }
        switch (exchange.getRequestMethod()) {
            case "PUT" -> {
                exchange.getResponseHeaders().set("ETag", "\"0123456789abcdef\"");
                respond(exchange, 200, null);
            }
            case "DELETE" -> respond(exchange, 204, null);
            case "GET" -> {
                if (uri.getRawQuery() != null && uri.getRawQuery().contains("list-type=2")) {
                    respond(exchange, 200, listing(uri.getRawQuery()));
                } else {
                    object(exchange);
                }
            }
            default -> respond(exchange, 405, null);
        }
    }

    private String listing(String query) {
        String prefix = "";
        int maxKeys = 1000;
        for (String pair : query == null ? new String[0] : query.split("&")) {
            String[] kv = pair.split("=", 2);
            String value = kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            if (kv[0].equals("prefix")) {
                prefix = value;
            } else if (kv[0].equals("max-keys")) {
                maxKeys = Integer.parseInt(value);
            }
        }
        List<String> matching = new ArrayList<>();
        for (String key : keys) {
            if (key.startsWith(prefix) && matching.size() < maxKeys) {
                matching.add(key);
            }
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>bucket</Name>"
                + "<Prefix>" + prefix + "</Prefix><KeyCount>" + matching.size() + "</KeyCount><MaxKeys>" + maxKeys
                + "</MaxKeys><IsTruncated>false</IsTruncated>");
        for (String key : matching) {
            xml.append("<Contents><Key>").append(key).append("</Key><LastModified>2026-11-07T12:00:00.000Z"
                    + "</LastModified><Size>3</Size><StorageClass>STANDARD</StorageClass></Contents>");
        }
        return xml.append("</ListBucketResult>").toString();
    }

    /** A GetObject: every object holds {@link #OBJECT_BYTES}. */
    private static void object(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, OBJECT_BYTES.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(OBJECT_BYTES);
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            if (body == null) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
