package com.matheusmelo.kybr.config.management;

import com.matheusmelo.kybr.config.filter.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One INFO line per management call other than the open probes (health and Prometheus, which
 * are scraped every few seconds): {@code method}, {@code operation} (the path, never the query),
 * {@code status}, {@code token_slot} (or {@code -}), {@code duration_ms} and {@code request_id}.
 * Never the body, never a header, never the token.
 */
public class ManagementAccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ManagementAccessLogFilter.class);

    private final RequestMatcher openProbes;

    public ManagementAccessLogFilter(RequestMatcher openProbes) {
        this.openProbes = openProbes;
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
                    request.getMethod(), request.getRequestURI(), status, slot == null ? "-" : slot,
                    (System.nanoTime() - start) / 1_000_000, request.getAttribute(RequestIdFilter.ATTRIBUTE));
        }
    }
}
