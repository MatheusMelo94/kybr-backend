package com.matheusmelo.kybr.config.management;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.SecurityConfig;
import com.matheusmelo.kybr.controller.admin.AdminEndpoint;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The management server's security chain: deny by default. It applies to every request the
 * management server receives (marked by {@link ManagementRequestFilter}, which exists only in the
 * management context) and to nothing on the public port.
 *
 * <ul>
 *   <li>The admin endpoint requires an admin bearer token ({@link AdminBearerFilter}); without a
 *       valid one: 401 ({@link AdminAuthFailureHandler}).
 *   <li>{@code health} and {@code prometheus} are open (the platform probes and scrapes them).
 *   <li>Everything else is denied, with or without a token: 403 with an empty body. An endpoint
 *       or path added later stays denied until a rule here names it.
 * </ul>
 *
 * Also the startup guard: the admin endpoint is never served on the public port
 * ({@link ManagementPortGuard}).
 */
@Configuration(proxyBeanMethods = false)
public class ManagementSecurityConfig {

    ManagementSecurityConfig(Environment environment) {
        ManagementPortGuard.check(environment);
    }

    /** The probes the platform reads without a token. */
    static RequestMatcher openProbes() {
        return EndpointRequest.to("health", "prometheus");
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain managementSecurityChain(HttpSecurity http, KybrProperties properties,
            MeterRegistry meters) throws Exception {
        RequestMatcher admin = EndpointRequest.to(AdminEndpoint.class);
        AdminAuthFailureHandler authFailure = new AdminAuthFailureHandler(meters);
        AdminTokenVerifier verifier = new AdminTokenVerifier(properties.admin().token(),
                properties.admin().tokenPrevious());
        AuthenticationEntryPoint unauthenticated = (request, response, ex) -> {
            if (admin.matches(request)) {
                authFailure.commence(request, response, ex);
            } else {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            }
        };
        AccessDeniedHandler denied = (request, response, ex) -> response.setStatus(HttpServletResponse.SC_FORBIDDEN);

        SecurityConfig.statelessApiDefaults(http, unauthenticated, denied);
        return http.securityMatcher(ManagementRequestFilter::isManagement)
                .addFilterBefore(new AdminBearerFilter(verifier), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(openProbes()).permitAll()
                        .requestMatchers(admin).hasRole(AdminBearerFilter.ROLE)
                        .anyRequest().denyAll())
                .build();
    }
}
