package com.matheusmelo.kybr.support.trust;

import com.matheusmelo.kybr.config.filter.FilterOrder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Test-only: counts requests that got past the path firewall (runs right after it). */
@Component
@FilterRegistration(order = FilterOrder.PATH_FIREWALL + 1)
public class LaterFilterProbe extends OncePerRequestFilter {

    public static final AtomicInteger PASSED = new AtomicInteger();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PASSED.incrementAndGet();
        chain.doFilter(request, response);
    }
}
