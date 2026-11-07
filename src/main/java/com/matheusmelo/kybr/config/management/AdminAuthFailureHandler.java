package com.matheusmelo.kybr.config.management;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * A call to the admin endpoint without a valid token: 401 with an empty body and a bare
 * {@code Bearer} challenge (no reason), one WARN {@code event=admin_auth_failed} saying only
 * whether a credential was sent, and {@code kybr.admin.auth_failed} +1 (alerted on).
 */
public class AdminAuthFailureHandler implements AuthenticationEntryPoint {

    public static final String METRIC = "kybr.admin.auth_failed";

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFailureHandler.class);

    private final Counter failures;

    public AdminAuthFailureHandler(MeterRegistry meters) {
        this.failures = Counter.builder(METRIC).description("Admin calls refused for a missing or wrong token")
                .register(meters);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) {
        failures.increment();
        String credential = request.getHeader(HttpHeaders.AUTHORIZATION) == null ? "absent" : "invalid";
        log.warn("event=admin_auth_failed method={} credential={}", request.getMethod(), credential);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }
}
