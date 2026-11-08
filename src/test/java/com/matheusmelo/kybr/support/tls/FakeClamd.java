package com.matheusmelo.kybr.support.tls;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * A clamd stand-in behind TLS 1.3 with client-certificate authentication, speaking enough of the
 * protocol for a client test: {@code zINSTREAM} (length-prefixed chunks, then a zero length) and
 * {@code zVERSION}. Its reply is scripted; each served command is recorded. One connection at a time.
 */
public final class FakeClamd implements AutoCloseable {

    /** How the server answers. */
    public enum Mode { REPLY, SILENT, RESET }

    /** One served command: the command and, for a stream, each chunk's length. */
    public record Served(String command, List<Integer> chunkLengths, long bytes) {
    }

    private final SSLServerSocket server;
    private final Thread acceptor;
    private final List<Served> served = new CopyOnWriteArrayList<>();
    private volatile Function<String, byte[]> reply = command -> "stream: OK\0".getBytes(StandardCharsets.US_ASCII);
    private volatile Mode mode = Mode.REPLY;

    public FakeClamd(SSLContext context) throws IOException {
        server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 5,
                InetAddress.getLoopbackAddress());
        server.setNeedClientAuth(true);
        server.setEnabledProtocols(new String[] {"TLSv1.3"});
        acceptor = Thread.ofVirtual().start(this::serve);
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Answers every command with {@code bytes} exactly (add the NUL yourself). */
    public FakeClamd replying(String bytes) {
        byte[] raw = bytes.getBytes(StandardCharsets.US_ASCII);
        reply = command -> raw;
        mode = Mode.REPLY;
        return this;
    }

    public FakeClamd replying(Function<String, byte[]> script) {
        reply = script;
        mode = Mode.REPLY;
        return this;
    }

    public FakeClamd mode(Mode value) {
        mode = value;
        return this;
    }

    public List<Served> served() {
        return List.copyOf(served);
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout((int) Duration.ofSeconds(10).toMillis());
                handle(socket);
            } catch (IOException e) {
                // a failed handshake or a client gone: wait for the next connection
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        StringBuilder command = new StringBuilder();
        int b;
        while ((b = in.read()) > 0) {
            command.append((char) b);
        }
        List<Integer> chunks = new ArrayList<>();
        long bytes = 0;
        if (command.toString().equals("zINSTREAM")) {
            int length;
            while ((length = in.readInt()) > 0) {
                in.readNBytes(length);
                chunks.add(length);
                bytes += length;
            }
        }
        served.add(new Served(command.toString(), chunks, bytes));
        switch (mode) {
            case SILENT -> {
                try {
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            case RESET -> socket.setSoLinger(true, 0);
            case REPLY -> {
                OutputStream out = socket.getOutputStream();
                out.write(reply.apply(command.toString()));
                out.flush();
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        acceptor.interrupt();
    }
}
