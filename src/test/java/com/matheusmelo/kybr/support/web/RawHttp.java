package com.matheusmelo.kybr.support.web;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sends a raw HTTP/1.1 request over a socket, for requests HTTP clients refuse to build (bad
 * paths, oversized headers, control characters). Reads until the server closes the connection.
 */
public final class RawHttp {

    private static final int SLICE = 64 * 1024;

    public record Response(int status, Map<String, String> headers, String body) {

        /** A header value by case-insensitive name, or null. */
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private RawHttp() {
    }

    /** {@code requestHead} is everything before the blank line, without the final CRLF. */
    public static Response send(int port, String requestHead) throws IOException {
        return send(port, requestHead.getBytes(StandardCharsets.ISO_8859_1), new byte[0]);
    }

    public static Response send(int port, byte[] requestHead, byte[] body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(requestHead);
            out.write("\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            return parse(readAll(socket.getInputStream()));
        }
    }

    /**
     * What happened to a request whose body the server may stop reading part-way: either a parsed
     * response, or the broken pipe / connection reset the client got while still sending.
     *
     * @param bytesSent how many body bytes the client wrote before the response or the failure
     */
    public record Outcome(Response response, IOException closedEarly, long bytesSent) {
    }

    /**
     * Like {@link #send(int, byte[], byte[])}, but for bodies the server is entitled to stop
     * reading: once it has answered (or decided not to read on), it closes the connection, and the
     * client can see a broken pipe or a connection reset before it reads anything. A response that
     * still arrives after a failed write is returned as the response; no response at all is
     * {@code closedEarly}.
     */
    public static Outcome sendUntilClosed(int port, byte[] requestHead, byte[] body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(requestHead);
            out.write("\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            long sent = 0;
            try {
                for (int offset = 0; offset < body.length; offset += SLICE) {
                    int length = Math.min(SLICE, body.length - offset);
                    out.write(body, offset, length);
                    sent += length;
                }
                out.flush();
            } catch (IOException closed) {
                if (!isEarlyClose(closed)) {
                    throw closed;
                }
                return outcome(parse(readAll(socket.getInputStream())), closed, sent);
            }
            return outcome(parse(readAll(socket.getInputStream())),
                    new EOFException("connection closed before a response"), sent);
        }
    }

    private static Outcome outcome(Response response, IOException ifNone, long sent) {
        return response.status() > 0 ? new Outcome(response, null, sent) : new Outcome(null, ifNone, sent);
    }

    private static boolean isEarlyClose(IOException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("broken pipe") || message.contains("connection reset");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            in.transferTo(buffer);
        } catch (IOException connectionReset) {
            // The server may reset after answering; keep what arrived.
        }
        return buffer.toByteArray();
    }

    private static Response parse(byte[] raw) {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int split = text.indexOf("\r\n\r\n");
        String head = split < 0 ? text : text.substring(0, split);
        String body = split < 0 ? "" : text.substring(split + 4);
        String[] lines = head.split("\r\n");
        int status = lines[0].isEmpty() ? -1 : Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            body = dechunk(body);
        }
        return new Response(status, headers, body);
    }

    private static String dechunk(String chunked) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (pos < chunked.length()) {
            int lineEnd = chunked.indexOf("\r\n", pos);
            if (lineEnd < 0) {
                break;
            }
            int size = Integer.parseInt(chunked.substring(pos, lineEnd).split(";")[0].trim(), 16);
            if (size == 0) {
                break;
            }
            out.append(chunked, lineEnd + 2, lineEnd + 2 + size);
            pos = lineEnd + 2 + size + 2;
        }
        return out.toString();
    }
}
