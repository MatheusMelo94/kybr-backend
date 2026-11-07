package com.matheusmelo.kybr.config.management;

import com.matheusmelo.kybr.config.filter.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import com.matheusmelo.kybr.util.LogRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * One INFO line per management call other than the open probes (health and Prometheus, which
 * are scraped every few seconds): {@code method} (a standard HTTP method, else {@code OTHER}),
 * {@code operation} (the matched handler pattern, else the path, never the query, with control
 * characters replaced, redacted and length-capped by {@link LogRedactor#redactPath}),
 * {@code status}, {@code token_slot} (or {@code -}), {@code duration_ms} and {@code request_id}.
 * Never the body, never a header, never the token.
 */
public class ManagementAccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ManagementAccessLogFilter.class);

    /** The methods logged by name; anything else is logged as {@code OTHER}. */
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS",
            "TRACE");

    private final RequestMatcher openProbes;
    private final LogRedactor redactor;

    public ManagementAccessLogFilter(RequestMatcher openProbes, LogRedactor redactor) {
        this.openProbes = openProbes;
        this.redactor = redactor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (openProbes.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        long start = System.nanoTime();
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            Object slot = request.getAttribute(AdminBearerFilter.SLOT_ATTRIBUTE);
            log.info("event=admin_access method={} operation={} status={} token_slot={} duration_ms={} request_id={}",
                    method(request), operation(request), status, slot == null ? "-" : slot,
                    (System.nanoTime() - start) / 1_000_000, request.getAttribute(RequestIdFilter.ATTRIBUTE));
        }
    }

    private static String method(HttpServletRequest request) {
        String method = request.getMethod();
        return method != null && METHODS.contains(method) ? method : "OTHER";
    }

    /** The matched handler pattern; otherwise the raw path made safe for one log line. */
    private String operation(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String template) {
            return template;
        }
        String path = redactor.redactPath(request.getRequestURI());
        return path == null || path.isEmpty() ? "-" : path;
    }
}
