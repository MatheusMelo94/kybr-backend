package com.matheusmelo.kybr.config.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.core.Ordered;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

/**
 * Filter [8]: buffers the body of every POST/PUT JSON request (any spelling:
 * {@code application/json}, {@code +json}, any case or parameters) so the idempotency interceptor
 * can hash it and binding can still read it. The size is bounded by filter [5]: an oversized body
 * throws {@code PayloadTooLargeException} while being read here, and it propagates (rendered as 413).
 * A body that filter [5] didn't wrap is never read (nothing reads an uncapped body): a wiring error,
 * not a request.
 *
 * <p>It also wraps every response in a {@link CapturingResponse}: a pass-through tee that copies
 * what is written only once the interceptor enables capture on an {@code @Idempotent} route.
 * Nothing is ever buffered or delayed on the way to the client.
 */
@Component
@FilterRegistration(order = FilterOrder.CACHED_BODY)
public class CachedBodyFilter extends OncePerRequestFilter implements Ordered {

    /** The request attribute holding the buffered body bytes. */
    public static final String BODY_ATTRIBUTE = CachedBodyFilter.class.getName() + ".body";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest next = request;
        if (isBufferable(request)) {
            if (!BodySizeLimitFilter.isLimited(request)) {
                // Defense in depth: never buffer a body nothing caps.
                throw new IllegalStateException("BodySizeLimitFilter must wrap a request before it is buffered");
            }
            byte[] body = request.getInputStream().readAllBytes();
            request.setAttribute(BODY_ATTRIBUTE, body);
            next = new CachedBodyRequest(request, body);
        }
        CapturingResponse tee = new CapturingResponse(response);
        try {
            chain.doFilter(next, tee);
        } finally {
            // The container finishes its own response, not this writer: drain it.
            tee.drainWriter();
        }
    }

    /** The body buffered by this filter, if the request was a POST/PUT JSON request. */
    public static Optional<byte[]> cachedBody(HttpServletRequest request) {
        return request.getAttribute(BODY_ATTRIBUTE) instanceof byte[] body ? Optional.of(body) : Optional.empty();
    }

    /** This filter's tee somewhere in the response wrapper chain, or null if it didn't run. */
    public static CapturingResponse capturingResponse(HttpServletResponse response) {
        return WebUtils.getNativeResponse(response, CapturingResponse.class);
    }

    static boolean isBufferable(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"POST".equals(method) && !"PUT".equals(method) || request.getContentType() == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType());
            return "application".equals(type.getType())
                    && ("json".equals(type.getSubtype()) || type.getSubtype().endsWith("+json"));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        return FilterOrder.CACHED_BODY;
    }

    /** Serves the buffered body to every reader, as often as asked. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("async reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    /** A pass-through tee; copies the body written after {@link #startCapture()}. */
    public static final class CapturingResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream copy = new ByteArrayOutputStream();
        private volatile boolean capturing;
        private volatile boolean holding;
        private ServletOutputStream stream;
        private PrintWriter writer;
        private boolean draining;

        CapturingResponse(HttpServletResponse response) {
            super(response);
        }

        public void startCapture() {
            capturing = true;
        }

        /**
         * Stops flushes from committing the response, so it can still be reset after the handler
         * has written it. The container commits it when the request ends (or when its buffer fills).
         */
        public void holdCommit() {
            holding = true;
        }

        /** What was written while capturing (writer drained first). */
        public byte[] captured() {
            drainWriter();
            return copy.toByteArray();
        }

        /**
         * Moves the writer's encoded text into the stream without flushing the stream, so the
         * response isn't committed and outer filters can still reset it.
         */
        void drainWriter() {
            if (writer != null) {
                draining = true;
                try {
                    writer.flush();
                } finally {
                    draining = false;
                }
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                stream = new TeeOutputStream(super.getOutputStream());
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset));
            }
            return writer;
        }

        /** Discards the captured copy too, so a stored replay equals what the client got. */
        @Override
        public void resetBuffer() {
            drainWriter();
            super.resetBuffer();
            copy.reset();
        }

        @Override
        public void reset() {
            drainWriter();
            super.reset();
            copy.reset();
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) {
                writer.flush();
            }
            if (!holding) {
                super.flushBuffer();
            }
        }

        private final class TeeOutputStream extends ServletOutputStream {
            private final ServletOutputStream target;

            TeeOutputStream(ServletOutputStream target) {
                this.target = target;
            }

            @Override
            public void write(int b) throws IOException {
                target.write(b);
                if (capturing) {
                    copy.write(b);
                }
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                target.write(bytes, offset, length);
                if (capturing) {
                    copy.write(bytes, offset, length);
                }
            }

            @Override
            public void flush() throws IOException {
                if (!draining && !holding) {
                    target.flush();
                }
            }

            @Override
            public void close() throws IOException {
                target.close();
            }

            @Override
            public boolean isReady() {
                return target.isReady();
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                target.setWriteListener(listener);
            }
        }
    }
}
