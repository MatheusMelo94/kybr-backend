package com.matheusmelo.kybr.config.filter;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.KybrProperties.Route;
import com.matheusmelo.kybr.model.BffKeySlot;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.service.ClientIpResolver;
import com.matheusmelo.kybr.service.ClientIpResolver.Resolution;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter [7]: resolves the request's {@link ClientIp} with
 * {@link ClientIpResolver} from filter [6]'s {@link BffKeySlot}, sets the request attribute and MDC
 * {@code client_ip}/{@code client_ip_source} (cleared by filter [1]), and emits:
 *
 * <ul>
 *   <li>WARN {@code event=client_ip_absent} + {@code kybr.client_ip.absent{esperado="true"}}: valid
 *       key, no usable {@code X-Kybr-Client-IP}. On a route listed in
 *       {@code kybr.client-ip.not-expected} (the BFF sends no client IP there by design) it is DEBUG
 *       and {@code esperado="false"} instead, so only the alert-worthy case is counted as expected.
 *       The resolved source is {@code FORWARDED_ABSENT} either way;
 *   <li>WARN {@code event=client_ip_platform_absent reason=…} +
 *       {@code kybr.client_ip.platform_absent{reason}}: the platform header is missing, invalid or
 *       repeated; {@code reason=missing} on {@code /livez} is DEBUG (Fly's own health checks);
 *   <li>INFO {@code event=client_ip_untrusted}: an {@code X-Kybr-Client-IP} sent without a key.
 * </ul>
 *
 * <p>{@link BffKeyFilter} calls {@link #record} before it rejects a request, so a 403 is attributed
 * to the right IP too (the access log reads it). Header values are never logged.
 */
@Component
@FilterRegistration(order = FilterOrder.CLIENT_IP)
public class ClientIpFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(ClientIpFilter.class);

    public static final String CLIENT_IP_HEADER = "X-Kybr-Client-IP";
    public static final String MDC_IP = "client_ip";
    public static final String MDC_SOURCE = "client_ip_source";
    private static final String LIVEZ = "/livez";

    private final ClientIpResolver resolver;
    private final MeterRegistry meters;
    private final List<RouteMatcher> notExpected;

    @Autowired
    public ClientIpFilter(ClientIpResolver resolver, MeterRegistry meters, KybrProperties properties) {
        this(resolver, meters, properties.clientIp().notExpected());
    }

    /** No route is exempt from expecting a client IP. */
    public ClientIpFilter(ClientIpResolver resolver, MeterRegistry meters) {
        this(resolver, meters, List.of());
    }

    public ClientIpFilter(ClientIpResolver resolver, MeterRegistry meters, List<Route> notExpected) {
        this.resolver = resolver;
        this.meters = meters;
        this.notExpected = notExpected.stream().map(route -> RouteMatcher.of(route.method(), route.pattern()))
                .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        record(request);
        chain.doFilter(request, response);
    }

    /** Resolves, stores and reports the client IP for this request (idempotent per request). */
    public void record(HttpServletRequest request) {
        if (request.getAttribute(ClientIp.ATTRIBUTE) instanceof ClientIp) {
            return;
        }
        BffKeySlot slot = request.getAttribute(BffKeySlot.ATTRIBUTE) instanceof BffKeySlot s ? s : BffKeySlot.NONE;
        String platformHeader = resolver.platformHeader();
        List<String> platformValues = platformHeader == null ? List.of()
                : Collections.list(request.getHeaders(platformHeader));
        Resolution resolution = resolver.resolve(slot, Collections.list(request.getHeaders(CLIENT_IP_HEADER)),
                platformValues, request.getRemoteAddr());

        ClientIp clientIp = resolution.clientIp();
        request.setAttribute(ClientIp.ATTRIBUTE, clientIp);
        MDC.put(MDC_IP, clientIp.ip());
        MDC.put(MDC_SOURCE, clientIp.source().name());
        report(request, resolution);
    }

    private void report(HttpServletRequest request, Resolution resolution) {
        if (!resolution.platformOk()) {
            String reason = resolution.platformReason();
            meters.counter("kybr.client_ip.platform_absent", "reason", reason).increment();
            if ("missing".equals(reason) && isLivez(request)) {
                log.debug("event=client_ip_platform_absent reason={}", reason);
            } else {
                log.warn("event=client_ip_platform_absent reason={}", reason);
            }
        }
        switch (resolution.clientIp().source()) {
            case FORWARDED_ABSENT -> {
                boolean expected = clientIpExpected(request);
                meters.counter("kybr.client_ip.absent", "esperado", String.valueOf(expected)).increment();
                if (expected) {
                    log.warn("event=client_ip_absent");
                } else {
                    log.debug("event=client_ip_absent esperado=false");
                }
            }
            case DIRECT -> {
                if (resolution.clientHeaderPresent()) {
                    log.info("event=client_ip_untrusted");
                }
            }
            default -> {
            }
        }
    }

    /** False on a not-expected route; true elsewhere, and when no parsed path was cached. */
    private boolean clientIpExpected(HttpServletRequest request) {
        return request.getAttribute(PathFirewallFilter.REQUEST_PATH_ATTRIBUTE) == null
                || notExpected.stream().noneMatch(route -> route.matches(request));
    }

    private static boolean isLivez(HttpServletRequest request) {
        Object path = request.getAttribute(PathFirewallFilter.REQUEST_PATH_ATTRIBUTE);
        return path != null && LIVEZ.equals(PathFirewallFilter.requestPath(request).pathWithinApplication().value());
    }

    @Override
    public int getOrder() {
        return FilterOrder.CLIENT_IP;
    }
}
