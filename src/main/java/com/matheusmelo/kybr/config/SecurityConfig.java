package com.matheusmelo.kybr.config;

import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The Spring Security chain (filter [9]): stateless, no CSRF (no ambient credentials),
 * no form login, HTTP Basic, logout or request cache, and its header writer disabled
 * ({@code ResponseGuardFilter} is the single header writer). B0 permits every request; B3 adds
 * the session requirement.
 *
 * <p>Failures are envelopes, never a challenge, redirect or HTML page:
 * <ul>
 *   <li>Authentication required: 401 {@code SESSAO_EXPIRADA} (the contract's code for a missing,
 *       expired or revoked session, which B3 keeps).
 *   <li>Access denied: 404 {@code RECURSO_NAO_ENCONTRADO}, the same body as an unknown route
 *       (a deny looks like not found, so it reveals nothing; v1 has no roles).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityChain(HttpSecurity http, AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler deniedHandler) throws Exception {
        statelessApiDefaults(http, entryPoint, deniedHandler);
        return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ErrorResponseWriter writer) {
        return (request, response, ex) -> writer.write(response, ErrorCode.SESSAO_EXPIRADA);
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler(ErrorResponseWriter writer) {
        return (request, response, ex) -> writer.write(response, ErrorCode.RECURSO_NAO_ENCONTRADO);
    }

    /** The settings every chain in this API shares. */
    public static void statelessApiDefaults(HttpSecurity http, AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler deniedHandler) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));
    }

    /**
     * For a chain whose routes nobody may reach (a deny-all fallback): anonymous callers get the
     * not-found answer too, instead of being asked to authenticate.
     */
    public static void denyAsNotFound(HttpSecurity http, AccessDeniedHandler deniedHandler) throws Exception {
        http.exceptionHandling(handling -> handling.authenticationEntryPoint((request, response, ex) ->
                deniedHandler.handle(request, response, new AccessDeniedException("denied"))));
    }
}
