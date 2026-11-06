package com.matheusmelo.kybr.support.web;

import com.matheusmelo.kybr.config.SecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/** Test-only chain: {@code /test/web/denied/**} is deny-all, built with the production defaults. */
@Configuration(proxyBeanMethods = false)
public class DenyAllTestSecurity {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain denyAllTestChain(HttpSecurity http, AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler deniedHandler) throws Exception {
        SecurityConfig.statelessApiDefaults(http, entryPoint, deniedHandler);
        SecurityConfig.denyAsNotFound(http, deniedHandler);
        return http.securityMatcher("/test/web/denied/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .build();
    }
}
