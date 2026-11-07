package com.matheusmelo.kybr.config.management;

import com.matheusmelo.kybr.config.filter.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The management server's outermost filter (it runs in the management context only, never on the
 * public port). It marks the request as a management request, which is what selects the
 * management security chain, and gives it a fresh {@code X-Request-Id} (never echoing an inbound
 * one) and MDC {@code request_id}, like the public port's request-id filter. Responses are never
 * cached or sniffed.
 */
public class ManagementRequestFilter extends OncePerRequestFilter {

    /** Present on every request the management server receives. */
    public static final String ATTRIBUTE = ManagementRequestFilter.class.getName() + ".management";

    /** Whether the request arrived on the management server. */
    public static boolean isManagement(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ATTRIBUTE));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(ATTRIBUTE, Boolean.TRUE);
        String id = request.getAttribute(RequestIdFilter.ATTRIBUTE) instanceof String existing ? existing
                : UUID.randomUUID().toString();
        request.setAttribute(RequestIdFilter.ATTRIBUTE, id);
        response.setHeader(RequestIdFilter.HEADER, id);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        MDC.put(RequestIdFilter.MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
