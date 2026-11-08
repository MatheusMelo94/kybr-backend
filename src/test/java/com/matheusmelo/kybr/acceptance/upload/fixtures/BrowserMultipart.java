package com.matheusmelo.kybr.acceptance.upload.fixtures;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * A byte-exact {@code multipart/form-data} body shaped the way a browser builds one from
 * {@code FormData}: a WebKit-style boundary, a {@code tipo} text part, then file parts carrying the
 * original file name (raw UTF-8, with {@code "}, CR and LF percent-encoded as browsers do) and the
 * part type the browser guessed. Optionally each name is also sent as RFC 5987 {@code filename*},
 * the largest part header a client can produce.
 */
public final class BrowserMultipart {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String boundary;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public BrowserMultipart() {
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            suffix.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        this.boundary = "----WebKitFormBoundary" + suffix;
    }

    public BrowserMultipart field(String name, String value) {
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public BrowserMultipart file(String name, String filename, String contentType, byte[] content) {
        return file(name, filename, contentType, content, false);
    }

    /** A file part whose name is sent both as {@code filename} and as {@code filename*}. */
    public BrowserMultipart fileWithBothNames(String name, String filename, String contentType, byte[] content) {
        return file(name, filename, contentType, content, true);
    }

    private BrowserMultipart file(String name, String filename, String contentType, byte[] content, boolean both) {
        String escaped = filename.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
        StringBuilder head = new StringBuilder("--").append(boundary)
                .append("\r\nContent-Disposition: form-data; name=\"").append(name).append("\"; filename=\"")
                .append(escaped).append('"');
        if (both) {
            head.append("; filename*=UTF-8''")
                    .append(URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        head.append("\r\nContent-Type: ").append(contentType).append("\r\n\r\n");
        out.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** The finished body, closing boundary included. */
    public byte[] body() {
        ByteArrayOutputStream finished = new ByteArrayOutputStream(out.size() + boundary.length() + 8);
        finished.writeBytes(out.toByteArray());
        finished.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return finished.toByteArray();
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    /**
     * The request head for {@code body} (everything before the blank line, without the final CRLF),
     * with an {@code Origin} header when {@code origin} isn't null.
     */
    public byte[] head(String path, byte[] body, String origin) {
        String head = "POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: " + contentType()
                + "\r\nContent-Length: " + body.length + (origin == null ? "" : "\r\nOrigin: " + origin);
        return head.getBytes(StandardCharsets.ISO_8859_1);
    }
}
