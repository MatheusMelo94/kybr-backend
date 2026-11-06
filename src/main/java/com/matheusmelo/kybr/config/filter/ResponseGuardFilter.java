package com.matheusmelo.kybr.config.filter;

import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter [2]: no redirects, no cookies, no sessions, and the security headers.
 *
 * <ul>
 *   <li>The full security header set is written <b>before</b> the chain runs, so errors written by
 *       later filters and by the error dispatch carry it too. It is the only header writer:
 *       Spring Security's is disabled.
 *   <li>{@code sendRedirect} or any 3xx status becomes 500 {@code ERRO_INTERNO}, with no
 *       {@code Location}, logged at ERROR {@code event=redirect_blocked}.
 *   <li>{@code addCookie} and {@code Set-Cookie} writes are dropped, logged at ERROR
 *       {@code event=cookie_blocked}.
 *   <li>Creating an {@code HttpSession} throws.
 * </ul>
 */
@Component
@FilterRegistration(order = FilterOrder.RESPONSE_GUARD,
        dispatcherTypes = {DispatcherType.REQUEST, DispatcherType.ERROR})
public class ResponseGuardFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(ResponseGuardFilter.class);

    private static final Map<String, String> SECURITY_HEADERS;
    private static final String HSTS_NAME = "Strict-Transport-Security";
    private static final String HSTS_VALUE = "max-age=31536000; includeSubDomains";

    static {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("X-Frame-Options", "DENY");
        headers.put("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        SECURITY_HEADERS = Map.copyOf(headers);
    }

    private final ErrorResponseWriter errorWriter;
    private final boolean hsts;

    public ResponseGuardFilter(ErrorResponseWriter errorWriter, Environment environment) {
        this.errorWriter = errorWriter;
        // TLS terminates at the Fly edge, so HSTS is sent unconditionally in the cloud profiles.
        this.hsts = hstsEnabled(environment);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        writeSecurityHeaders(response);
        GuardedResponse guarded = new GuardedResponse(response);
        chain.doFilter(new SessionlessRequest(request), guarded);
        if (guarded.redirectBlocked && !response.isCommitted()) {
            response.reset();
            if (request.getAttribute(RequestIdFilter.ATTRIBUTE) instanceof String requestId) {
                response.setHeader(RequestIdFilter.HEADER, requestId);
            }
            writeSecurityHeaders(response);
            errorWriter.write(response, ErrorCode.ERRO_INTERNO);
        }
    }

    private void writeSecurityHeaders(HttpServletResponse response) {
        securityHeaders(hsts).forEach(response::setHeader);
    }

    /** The security header set; HSTS included when {@code hsts} (staging and prod). */
    public static Map<String, String> securityHeaders(boolean hsts) {
        if (!hsts) {
            return SECURITY_HEADERS;
        }
        Map<String, String> headers = new LinkedHashMap<>(SECURITY_HEADERS);
        headers.put(HSTS_NAME, HSTS_VALUE);
        return headers;
    }

    /** Whether HSTS is sent in this environment (the staging and prod profiles). */
    public static boolean hstsEnabled(Environment environment) {
        return environment.matchesProfiles("staging", "prod");
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    public int getOrder() {
        return FilterOrder.RESPONSE_GUARD;
    }

    /** Throws on any attempt to create a session; there is never one to return. */
    private static final class SessionlessRequest extends HttpServletRequestWrapper {

        SessionlessRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public HttpSession getSession() {
            return getSession(true);
        }

        @Override
        public HttpSession getSession(boolean create) {
            if (create) {
                throw new IllegalStateException("HTTP sessions are disabled");
            }
            return null;
        }

        @Override
        public String changeSessionId() {
            throw new IllegalStateException("HTTP sessions are disabled");
        }
    }

    /**
     * Swallows redirects and cookies. Once a redirect is blocked, everything written afterwards is
     * discarded; the filter then replaces the response with the 500 envelope.
     */
    private static final class GuardedResponse extends HttpServletResponseWrapper {

        private boolean redirectBlocked;

        GuardedResponse(HttpServletResponse response) {
            super(response);
        }

        private void blockRedirect() {
            if (!redirectBlocked) {
                redirectBlocked = true;
                log.error("event=redirect_blocked");
            }
        }

        private static boolean isRedirect(int status) {
            return status >= 300 && status < 400;
        }

        private static boolean isCookieHeader(String name) {
            return HttpHeaders.SET_COOKIE.equalsIgnoreCase(name) || "Set-Cookie2".equalsIgnoreCase(name);
        }

        /** Whether a header write must be dropped. */
        private boolean drop(String name) {
            if (isCookieHeader(name)) {
                log.error("event=cookie_blocked");
                return true;
            }
            return redirectBlocked;
        }

        @Override
        public void setStatus(int status) {
            if (isRedirect(status)) {
                blockRedirect();
            } else if (!redirectBlocked) {
                super.setStatus(status);
            }
        }

        @Override
        public int getStatus() {
            return redirectBlocked ? ErrorCode.ERRO_INTERNO.status().value() : super.getStatus();
        }

        @Override
        public void sendError(int status) throws IOException {
            sendError(status, null);
        }

        @Override
        public void sendError(int status, String message) throws IOException {
            if (isRedirect(status)) {
                blockRedirect();
            } else if (!redirectBlocked) {
                super.sendError(status, message);
            }
        }

        @Override
        public void sendRedirect(String location) {
            blockRedirect();
        }

        @Override
        public void sendRedirect(String location, int status) {
            blockRedirect();
        }

        @Override
        public void sendRedirect(String location, boolean clearBuffer) {
            blockRedirect();
        }

        @Override
        public void sendRedirect(String location, int status, boolean clearBuffer) {
            blockRedirect();
        }

        @Override
        public void addCookie(Cookie cookie) {
            log.error("event=cookie_blocked");
        }

        @Override
        public void setHeader(String name, String value) {
            if (!drop(name)) {
                super.setHeader(name, value);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (!drop(name)) {
                super.addHeader(name, value);
            }
        }

        @Override
        public void setDateHeader(String name, long date) {
            if (!drop(name)) {
                super.setDateHeader(name, date);
            }
        }

        @Override
        public void addDateHeader(String name, long date) {
            if (!drop(name)) {
                super.addDateHeader(name, date);
            }
        }

        @Override
        public void setIntHeader(String name, int value) {
            if (!drop(name)) {
                super.setIntHeader(name, value);
            }
        }

        @Override
        public void addIntHeader(String name, int value) {
            if (!drop(name)) {
                super.addIntHeader(name, value);
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            return redirectBlocked ? DiscardingOutputStream.INSTANCE : super.getOutputStream();
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            return redirectBlocked ? new PrintWriter(OutputStream.nullOutputStream()) : super.getWriter();
        }

        @Override
        public void flushBuffer() throws IOException {
            if (!redirectBlocked) {
                super.flushBuffer();
            }
        }
    }

    private static final class DiscardingOutputStream extends ServletOutputStream {

        static final DiscardingOutputStream INSTANCE = new DiscardingOutputStream();

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
