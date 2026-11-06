package com.matheusmelo.kybr.support.web;

import com.matheusmelo.kybr.config.filter.FilterOrder;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Test-only filter that, when asked by {@value #HEADER}, writes an error envelope the way a later
 * pipeline filter does (403 BFF, 413), so tests can check what outer filters add to it.
 * Inert without the header.
 */
@Component
@FilterRegistration(order = FilterOrder.CACHED_BODY + 1)
public class FilterErrorProbe extends OncePerRequestFilter {

    public static final String HEADER = "X-Test-Filter-Error";

    private final ErrorResponseWriter writer;

    public FilterErrorProbe(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String code = request.getHeader(HEADER);
        if (code == null) {
            chain.doFilter(request, response);
            return;
        }
        writer.write(response, ErrorCode.valueOf(code));
    }
}
