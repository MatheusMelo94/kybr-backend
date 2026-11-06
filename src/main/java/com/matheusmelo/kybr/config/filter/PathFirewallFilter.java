package com.matheusmelo.kybr.config.filter;

import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.core.Ordered;
import org.springframework.http.server.RequestPath;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Filter [4]: applies Spring Security's {@link StrictHttpFirewall}
 * rules to every request ({@code //}, {@code ;} path parameters, encoded {@code /} {@code \}
 * {@code .}, {@code .}/{@code ..} segments, non-printable characters) and rejects a violation with
 * 400 {@code REQUISICAO_INVALIDA}. A passing request's path is parsed once, the way the dispatcher
 * parses it, and cached: filters [5] and [6] match only against {@link #requestPath}, never
 * {@code getRequestURI()}, so a path variant can't slip past their route rules.
 */
@Component
@FilterRegistration(order = FilterOrder.PATH_FIREWALL)
public class PathFirewallFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(PathFirewallFilter.class);

    /** The request attribute holding the normalised, parsed {@link RequestPath}. */
    public static final String REQUEST_PATH_ATTRIBUTE = PathFirewallFilter.class.getName() + ".path";

    private final StrictHttpFirewall firewall = new StrictHttpFirewall();
    private final ErrorResponseWriter writer;

    public PathFirewallFilter(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    /** The path that passed the firewall. Only valid after filter [4] has run. */
    public static RequestPath requestPath(HttpServletRequest request) {
        RequestPath path = (RequestPath) request.getAttribute(REQUEST_PATH_ATTRIBUTE);
        if (path == null) {
            throw new IllegalStateException("PathFirewallFilter has not run for this request");
        }
        return path;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            firewall.getFirewalledRequest(request);
        } catch (RequestRejectedException rejected) {
            // The exception text echoes the path, which may carry a token: never logged.
            log.info("event=path_rejected");
            writer.write(response, ErrorCode.REQUISICAO_INVALIDA);
            return;
        }
        request.setAttribute(REQUEST_PATH_ATTRIBUTE, ServletRequestPathUtils.parseAndCache(request));
        chain.doFilter(request, response);
    }

    @Override
    public int getOrder() {
        return FilterOrder.PATH_FIREWALL;
    }
}
