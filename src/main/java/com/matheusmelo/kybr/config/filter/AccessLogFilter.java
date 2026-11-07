package com.matheusmelo.kybr.config.filter;

import com.matheusmelo.kybr.model.BffKeySlot;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.util.LogRedactor;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Filter [3]: exactly one INFO line per request, with
 * {@code method}, {@code route}, {@code query_params} (names only), {@code status},
 * {@code duration_ms}, {@code request_id}, {@code client_ip}, {@code client_ip_source} and
 * {@code bff_key_slot}. No header is ever logged.
 *
 * <ul>
 *   <li>{@code route} is the matched handler pattern; otherwise the raw path, redacted (case tokens
 *       become {@code tok:} hashes). This filter sits outside the path firewall [4], so rejected
 *       raw-URI variants are logged too; that is why it reads the raw request URI, for logging
 *       only, never for a decision.
 *   <li>When the chain throws or calls {@code sendError}, the line waits for the container's
 *       {@code ERROR} dispatch, so the final status is logged.
 *   <li>Requests Tomcat rejects before any filter runs ({@code %2F}, bad percent-encoding, an
 *       oversized header) are logged by {@link AccessLogValve} instead, through {@link #write}.
 * </ul>
 *
 * <p>The fields are both in the message ({@code key=value}, readable in every format) and
 * structured fields in ECS: SLF4J key-value pairs, except those the MDC already holds for this
 * request (the MDC is their single source).
 */
@Component
@FilterRegistration(order = FilterOrder.ACCESS_LOG, dispatcherTypes = {DispatcherType.REQUEST, DispatcherType.ERROR})
public class AccessLogFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    /** Set on the request once its access line is written; the valve skips such requests. */
    static final String LOGGED_ATTRIBUTE = AccessLogFilter.class.getName() + ".logged";
    static final String NONE = "-";
    private static final String PENDING_ATTRIBUTE = AccessLogFilter.class.getName() + ".pending";

    private final LogRedactor redactor;

    public AccessLogFilter(LogRedactor redactor) {
        this.redactor = redactor;
    }

    /** What the request dispatch knows, kept for an error dispatch (whose URI is {@code /error}). */
    record Pending(long startNanos, String method, String route, String queryParams) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            filterErrorDispatch(request, response, chain);
            return;
        }
        long start = System.nanoTime();
        ErrorTrackingResponse tracked = new ErrorTrackingResponse(response);
        boolean failed = true;
        try {
            chain.doFilter(request, tracked);
            failed = false;
        } finally {
            Pending pending = new Pending(start, request.getMethod(), route(request),
                    queryParams(redactor, request.getQueryString()));
            if ((failed || tracked.errorSent) && !response.isCommitted()) {
                request.setAttribute(PENDING_ATTRIBUTE, pending);
            } else {
                write(request, response.getStatus(), pending);
            }
        }
    }

    private void filterErrorDispatch(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Pending pending = request.getAttribute(PENDING_ATTRIBUTE) instanceof Pending p ? p
                : new Pending(System.nanoTime(), request.getMethod(), redactedPath(redactor, errorUri(request)), "[]");
        try {
            chain.doFilter(request, response);
        } finally {
            write(request, response.getStatus(), pending);
        }
    }

    /** Writes the request's access line once; later calls for the same request do nothing. */
    static void write(jakarta.servlet.ServletRequest request, int status, Pending pending) {
        if (request.getAttribute(LOGGED_ATTRIBUTE) != null) {
            return;
        }
        request.setAttribute(LOGGED_ATTRIBUTE, Boolean.TRUE);
        ClientIp clientIp = request.getAttribute(ClientIp.ATTRIBUTE) instanceof ClientIp ip ? ip : null;
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("method", valueOr(pending.method()));
        fields.put("route", pending.route());
        fields.put("query_params", pending.queryParams());
        fields.put("status", status);
        fields.put("duration_ms", (System.nanoTime() - pending.startNanos()) / 1_000_000);
        fields.put("request_id", valueOr(requestId));
        fields.put("client_ip", clientIp == null ? NONE : clientIp.ip());
        fields.put("client_ip_source", clientIp == null ? NONE : clientIp.source().name());
        fields.put("bff_key_slot", valueOr(request.getAttribute(BffKeySlot.ATTRIBUTE)));

        LoggingEventBuilder line = log.atInfo().setMessage("event=access " + fields.entrySet().stream()
                .map(field -> field.getKey() + '=' + field.getValue())
                .collect(Collectors.joining(" ")));
        fields.forEach((key, value) -> {
            // A field the MDC already carries (request_id, client_ip, client_ip_source) is written
            // from the MDC only: the ECS writer rejects a duplicate name and drops the whole event.
            if (MDC.get(key) == null) {
                line.addKeyValue(key, value);
            }
        });
        line.log();
    }

    /** The matched handler pattern, or the redacted raw path when no handler matched. */
    private String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String template ? template : redactedPath(redactor, request.getRequestURI());
    }

    private static String errorUri(HttpServletRequest request) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return uri instanceof String s ? s : request.getRequestURI();
    }

    static String redactedPath(LogRedactor redactor, String rawPath) {
        return rawPath == null ? NONE : redactor.redactPath(rawPath);
    }

    /** {@code ["name", ...]}: query parameter names only (never values), redacted, in order. */
    static String queryParams(LogRedactor redactor, String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "[]";
        }
        Set<String> names = new LinkedHashSet<>();
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (!name.isEmpty()) {
                names.add(redactor.redact(name).replace("\\", "\\\\").replace("\"", "\\\""));
            }
        }
        return names.stream().map(name -> '"' + name + '"').collect(Collectors.joining(",", "[", "]"));
    }

    private static String valueOr(Object value) {
        return value == null ? NONE : value.toString();
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    public int getOrder() {
        return FilterOrder.ACCESS_LOG;
    }

    /** Notes {@code sendError}, after which the container answers through an error dispatch. */
    private static final class ErrorTrackingResponse extends HttpServletResponseWrapper {
        private boolean errorSent;

        ErrorTrackingResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void sendError(int status) throws IOException {
            errorSent = true;
            super.sendError(status);
        }

        @Override
        public void sendError(int status, String message) throws IOException {
            errorSent = true;
            super.sendError(status, message);
        }
    }
}
