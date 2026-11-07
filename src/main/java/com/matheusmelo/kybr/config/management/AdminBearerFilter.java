package com.matheusmelo.kybr.config.management;

import com.matheusmelo.kybr.model.link.TokenSlot;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates an operator from {@code Authorization: Bearer <token>} (exactly one such header,
 * the token byte-exact) against the current and previous admin tokens. A match authenticates the
 * request with the {@link #ROLE} and the slot as the principal's name, and records the slot on the
 * request for the access line. Anything else leaves the request anonymous: the chain's rules then
 * refuse it. It never decides access itself, and never logs.
 */
public class AdminBearerFilter extends OncePerRequestFilter {

    /** The authority an admin token grants. */
    public static final String ROLE = "OPERADOR";
    /** The {@link TokenSlot} that authenticated the request, when one did. */
    public static final String SLOT_ATTRIBUTE = AdminBearerFilter.class.getName() + ".slot";

    private static final String PREFIX = "Bearer ";

    private final AdminTokenVerifier verifier;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    public AdminBearerFilter(AdminTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        presentedToken(request).flatMap(verifier::verify).ifPresent(slot -> {
            request.setAttribute(SLOT_ATTRIBUTE, slot);
            SecurityContext context = contexts.createEmptyContext();
            context.setAuthentication(new PreAuthenticatedAuthenticationToken(slot.name(), null,
                    AuthorityUtils.createAuthorityList("ROLE_" + ROLE)));
            contexts.setContext(context);
        });
        chain.doFilter(request, response);
    }

    private static Optional<String> presentedToken(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (values.size() != 1 || !values.getFirst().startsWith(PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(values.getFirst().substring(PREFIX.length()));
    }
}
