package com.matheusmelo.kybr.config.filter;

import com.matheusmelo.kybr.config.KybrProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cross-origin access for the seller document upload, the one browser-direct route, and for nothing
 * else. It runs right after {@link RequestIdFilter}, ahead of every filter or handler that may write
 * a response, so an allowed origin can read every status, errors included: otherwise the browser
 * reports a network error and the seller resends the same refused file. The headers also survive a
 * later {@code reset()} and are restored on the {@code ERROR} dispatch, where the container has
 * cleared them.
 *
 * <p>The route is matched on the raw URI against a strict pattern (this filter runs before the path
 * firewall), so path variants get no headers. An allowed origin gets {@code Access-Control-Allow-Origin}
 * and the exposed headers; never credentials. A disallowed or missing origin is processed as any
 * other request ({@code Origin} is never a control): it only gets {@code Vary: Origin}, so the
 * browser hides the answer. A preflight is answered here with 204, carrying the allow headers only
 * when the origin, method and headers are all allowed. Spring's own CORS processing is never used for
 * this route.
 */
@Component
@FilterRegistration(order = D6CorsFilter.ORDER, dispatcherTypes = {DispatcherType.REQUEST, DispatcherType.ERROR})
public class D6CorsFilter extends OncePerRequestFilter implements Ordered {

    /** Right after {@link FilterOrder#REQUEST_ID}, before the response guard. */
    static final int ORDER = FilterOrder.REQUEST_ID + 50;

    static final Pattern ROUTE = Pattern.compile(
            "^/public/v/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?/cases/[A-Za-z0-9_-]{22,64}/documents$");
    static final String ALLOW_METHODS = "POST, OPTIONS";
    static final String ALLOW_HEADERS = "Content-Type";
    static final String EXPOSE_HEADERS = "Retry-After, " + RequestIdFilter.HEADER;
    static final String MAX_AGE = "600";

    /** The request attribute holding the allowed origin ({@code ""} when the route matched but not the origin). */
    private static final String ATTRIBUTE = D6CorsFilter.class.getName() + ".origin";

    private final List<String> origins;
    private final List<Pattern> originPatterns;
    private final Map<String, String> securityHeaders;

    @Autowired
    public D6CorsFilter(KybrProperties properties, Environment environment) {
        this(properties.cors().d6(), ResponseGuardFilter.hstsEnabled(environment));
    }

    D6CorsFilter(KybrProperties.D6Cors cors, boolean hsts) {
        this.origins = List.copyOf(cors.origins());
        this.originPatterns = cors.originPatterns().stream().map(Pattern::compile).toList();
        this.securityHeaders = ResponseGuardFilter.securityHeaders(hsts);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String allowedOrigin = decide(request);
        if (allowedOrigin == null) {
            chain.doFilter(request, response);
            return;
        }
        if (isPreflight(request)) {
            answerPreflight(request, response, allowedOrigin);
            return;
        }
        CorsResponse cors = new CorsResponse(response, allowedOrigin);
        cors.apply();
        chain.doFilter(request, cors);
    }

    /**
     * The allowed origin, {@code ""} for the upload route with an origin that isn't allowed, or
     * {@code null} for any other route. The decision of the first dispatch is reused on the error
     * dispatch.
     */
    private String decide(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String decided) {
            return decided;
        }
        String uri = request.getDispatcherType() == DispatcherType.ERROR
                ? (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String original ? original : null)
                : request.getRequestURI();
        if (uri == null || !ROUTE.matcher(uri).matches()) {
            return null;
        }
        String decided = allowed(request.getHeader(HttpHeaders.ORIGIN)) ? request.getHeader(HttpHeaders.ORIGIN) : "";
        request.setAttribute(ATTRIBUTE, decided);
        return decided;
    }

    private boolean allowed(String origin) {
        if (origin == null) {
            return false;
        }
        return origins.contains(origin) || originPatterns.stream().anyMatch(p -> p.matcher(origin).matches());
    }

    private static boolean isPreflight(HttpServletRequest request) {
        return request.getDispatcherType() == DispatcherType.REQUEST
                && "OPTIONS".equals(request.getMethod())
                && request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null;
    }

    private void answerPreflight(HttpServletRequest request, HttpServletResponse response, String allowedOrigin) {
        securityHeaders.forEach(response::setHeader);
        response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        if (!allowedOrigin.isEmpty() && preflightAllowed(request)) {
            writeOriginHeaders(response, allowedOrigin);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, ALLOW_METHODS);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, ALLOW_HEADERS);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, MAX_AGE);
        }
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private static boolean preflightAllowed(HttpServletRequest request) {
        String method = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
        if (!"POST".equals(method) && !"OPTIONS".equals(method)) {
            return false;
        }
        String headers = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
        return headers == null || Arrays.stream(headers.split(","))
                .map(h -> h.strip().toLowerCase(Locale.ROOT))
                .allMatch(h -> h.isEmpty() || h.equals("content-type"));
    }

    private static void writeOriginHeaders(HttpServletResponse response, String origin) {
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, EXPOSE_HEADERS);
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /** Puts the CORS headers back whenever a later filter resets the response. */
    private static final class CorsResponse extends HttpServletResponseWrapper {

        private final String allowedOrigin;

        CorsResponse(HttpServletResponse response, String allowedOrigin) {
            super(response);
            this.allowedOrigin = allowedOrigin;
        }

        void apply() {
            HttpServletResponse response = (HttpServletResponse) getResponse();
            if (!response.getHeaders(HttpHeaders.VARY).contains(HttpHeaders.ORIGIN)) {
                response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
            }
            if (!allowedOrigin.isEmpty()) {
                writeOriginHeaders(response, allowedOrigin);
            }
        }

        @Override
        public void reset() {
            super.reset();
            apply();
        }
    }
}
