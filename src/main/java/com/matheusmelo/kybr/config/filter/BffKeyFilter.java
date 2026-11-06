package com.matheusmelo.kybr.config.filter;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.KybrProperties.Bff;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import com.matheusmelo.kybr.model.BffKeySlot;
import com.matheusmelo.kybr.service.BffKeyVerifier;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter [6]: the BFF key is required on every request except the
 * configured exemptions (deny by default). Exemptions match exactly, on the normalised path cached
 * by {@link PathFirewallFilter}, never on the raw URI.
 *
 * <ul>
 *   <li>Missing, invalid or multiple values ({@code reason=missing|invalid|multiple}) get the same
 *       403 {@code BFF_KEY_AUSENTE}, a WARN {@code event=bff_key_rejected} without the value, and
 *       {@code kybr.bff_key.rejected{reason}}. Two header instances or a comma in the value count
 *       as multiple, and neither value is accepted. The client IP is recorded first
 *       ({@link ClientIpFilter#record}), so the rejection is attributed to the right address.
 *   <li>The header name is case-insensitive; the value is byte-exact.
 *   <li>A passing request gets the {@link BffKeySlot} attribute ({@code NONE} on an exempt route
 *       without a valid key, which only affects IP trust) and {@code kybr.bff_key.accepted{slot}}.
 *   <li>The key never grants authorization; and it is hidden from everything downstream.
 * </ul>
 */
@Component
@FilterRegistration(order = FilterOrder.BFF_KEY)
public class BffKeyFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(BffKeyFilter.class);

    public static final String HEADER = "X-Kybr-BFF-Key";

    /** Hashed in place of a missing key, so missing and invalid take comparable time. */
    private static final String ABSENT = "absent-key-placeholder";

    private enum Verdict {
        ACCEPTED, MISSING, INVALID, MULTIPLE
    }

    private final List<RouteMatcher> exemptions;
    private final BffKeyVerifier verifier;
    private final ErrorResponseWriter writer;
    private final MeterRegistry meters;
    private final ClientIpFilter clientIp;

    @Autowired
    public BffKeyFilter(KybrProperties properties, BffKeyVerifier verifier, ErrorResponseWriter writer,
            MeterRegistry meters, ClientIpFilter clientIp) {
        this(properties.bff(), verifier, writer, meters, clientIp);
    }

    public BffKeyFilter(Bff bff, BffKeyVerifier verifier, ErrorResponseWriter writer, MeterRegistry meters,
            ClientIpFilter clientIp) {
        this.exemptions = bff.exemptions().stream()
                .map(route -> RouteMatcher.of(route.method(), route.pattern()))
                .toList();
        this.verifier = verifier;
        this.writer = writer;
        this.meters = meters;
        this.clientIp = clientIp;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> values = Collections.list(request.getHeaders(HEADER));
        BffKeySlot slot = BffKeySlot.NONE;
        Verdict verdict;
        if (values.isEmpty()) {
            verifier.verify(ABSENT);
            verdict = Verdict.MISSING;
        } else if (values.size() > 1 || values.getFirst().contains(",")) {
            verifier.verify(ABSENT);
            verdict = Verdict.MULTIPLE;
        } else {
            slot = verifier.verify(values.getFirst());
            verdict = slot == BffKeySlot.NONE ? Verdict.INVALID : Verdict.ACCEPTED;
        }

        if (verdict != Verdict.ACCEPTED && !isExempt(request)) {
            String reason = verdict.name().toLowerCase(Locale.ROOT);
            // Attribute the rejection to the right IP (MDC, access log) before filter [7] is skipped.
            request.setAttribute(BffKeySlot.ATTRIBUTE, BffKeySlot.NONE);
            clientIp.record(request);
            log.warn("event=bff_key_rejected reason={}", reason);
            meters.counter("kybr.bff_key.rejected", "reason", reason).increment();
            writer.write(response, ErrorCode.BFF_KEY_AUSENTE);
            return;
        }
        if (verdict == Verdict.ACCEPTED) {
            meters.counter("kybr.bff_key.accepted", "slot", slot.name()).increment();
        }
        request.setAttribute(BffKeySlot.ATTRIBUTE, slot);
        chain.doFilter(new KeyHidingRequest(request), response);
    }

    private boolean isExempt(HttpServletRequest request) {
        return exemptions.stream().anyMatch(exemption -> exemption.matches(request));
    }

    @Override
    public int getOrder() {
        return FilterOrder.BFF_KEY;
    }

    /** Removes the key header from everything downstream (handlers, logging, error reports). */
    private static final class KeyHidingRequest extends HttpServletRequestWrapper {

        KeyHidingRequest(HttpServletRequest request) {
            super(request);
        }

        private static boolean isKey(String name) {
            return HEADER.equalsIgnoreCase(name);
        }

        @Override
        public String getHeader(String name) {
            return isKey(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isKey(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(KeyHidingRequest::isKey);
            return Collections.enumeration(names);
        }

        @Override
        public long getDateHeader(String name) {
            return isKey(name) ? -1 : super.getDateHeader(name);
        }

        @Override
        public int getIntHeader(String name) {
            return isKey(name) ? -1 : super.getIntHeader(name);
        }
    }
}
