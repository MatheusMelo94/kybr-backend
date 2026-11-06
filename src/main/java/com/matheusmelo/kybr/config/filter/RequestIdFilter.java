package com.matheusmelo.kybr.config.filter;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter [1]: a fresh UUID v4 per request, set as {@code X-Request-Id} on the response
 * before anything else runs, and put in MDC {@code request_id}. An inbound {@code X-Request-Id} is
 * never trusted or echoed.
 *
 * <p>Also runs on the {@code ERROR} dispatch, reusing the original request's id, because the
 * container resets the response headers before it dispatches to {@code /error}. As the
 * outermost filter it clears the whole MDC when the request ends.
 */
@Component
@FilterRegistration(order = FilterOrder.REQUEST_ID,
        dispatcherTypes = {DispatcherType.REQUEST, DispatcherType.ERROR})
public class RequestIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";
    /** The request attribute holding this request's id (a String). */
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = requestId(request);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private static String requestId(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String existing) {
            return existing;
        }
        String id = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    public int getOrder() {
        return FilterOrder.REQUEST_ID;
    }
}
