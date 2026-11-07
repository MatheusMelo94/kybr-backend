package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties.Route;
import com.matheusmelo.kybr.model.BffKeySlot;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.ClientIp.Source;
import com.matheusmelo.kybr.service.ClientIpResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ServletRequestPathUtils;

/** Documentation addresses only (RFC 5737). */
@ExtendWith(OutputCaptureExtension.class)
class ClientIpFilterTest {

    private static final String FLY = "Fly-Client-IP";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private static final String LINK = "/public/v/{slug}";
    private static final String UPLOAD = "/public/v/{slug}/cases/{token}/documents";

    private final SimpleMeterRegistry notExpectedMeters = new SimpleMeterRegistry();
    private final ClientIpFilter fly = new ClientIpFilter(new ClientIpResolver(FLY), meters);
    private final ClientIpFilter withRoutes = new ClientIpFilter(new ClientIpResolver(FLY), notExpectedMeters,
            List.of(new Route(HttpMethod.GET, LINK), new Route(HttpMethod.GET, "/livez"),
                    new Route(HttpMethod.POST, UPLOAD), new Route(HttpMethod.OPTIONS, UPLOAD)));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static MockHttpServletRequest request(String path, BffKeySlot slot) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("192.0.2.10");
        request.setAttribute(BffKeySlot.ATTRIBUTE, slot);
        request.setAttribute(PathFirewallFilter.REQUEST_PATH_ATTRIBUTE, ServletRequestPathUtils.parseAndCache(request));
        return request;
    }

    private ClientIp run(MockHttpServletRequest request) throws Exception {
        AtomicReference<ClientIp> seen = new AtomicReference<>();
        fly.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            seen.set((ClientIp) ((HttpServletRequest) req).getAttribute(ClientIp.ATTRIBUTE));
            assertThat(MDC.get(ClientIpFilter.MDC_IP)).isEqualTo(seen.get().ip());
            assertThat(MDC.get(ClientIpFilter.MDC_SOURCE)).isEqualTo(seen.get().source().name());
        });
        return seen.get();
    }

    private ClientIp resolve(ClientIpFilter filter, MockHttpServletRequest request) throws Exception {
        AtomicReference<ClientIp> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set((ClientIp) ((HttpServletRequest) req).getAttribute(ClientIp.ATTRIBUTE)));
        return seen.get();
    }

    private double absent(String esperado) {
        var counter = notExpectedMeters.find("kybr.client_ip.absent").tags("esperado", esperado).counter();
        return counter == null ? 0 : counter.count();
    }

    private double count(String meter, String... tags) {
        var counter = meters.find(meter).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theBffSpoofCaseTrustsOnlyTheKeyedHeader() throws Exception {
        MockHttpServletRequest keyed = request("/v1/x", BffKeySlot.CURRENT);
        keyed.addHeader("X-Kybr-Client-IP", "198.51.100.4");
        keyed.addHeader("X-Forwarded-For", "203.0.113.7");
        keyed.addHeader(FLY, "203.0.113.8");

        assertThat(run(keyed)).isEqualTo(new ClientIp("198.51.100.4", Source.FORWARDED));

        MockHttpServletRequest unkeyed = request("/livez", BffKeySlot.NONE);
        unkeyed.addHeader("X-Kybr-Client-IP", "198.51.100.4");
        unkeyed.addHeader("X-Forwarded-For", "203.0.113.7");
        unkeyed.addHeader(FLY, "203.0.113.8");

        assertThat(run(unkeyed)).isEqualTo(new ClientIp("203.0.113.8", Source.DIRECT));
    }

    @Test
    void aValidKeyWithoutTheHeaderIsAbsent(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = request("/v1/x", BffKeySlot.CURRENT);
        request.addHeader(FLY, "198.51.100.7");

        assertThat(run(request)).isEqualTo(new ClientIp("198.51.100.7", Source.FORWARDED_ABSENT));
        assertThat(output).contains("WARN").contains("event=client_ip_absent");
        assertThat(count("kybr.client_ip.absent")).isEqualTo(1);
    }

    @Test
    void aMissingClientIpWhereNoneIsExpectedIsOnlyDebug(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = request("/public/v/loja-exemplo", BffKeySlot.CURRENT);
        request.addHeader(FLY, "198.51.100.7");

        ClientIp resolved = resolve(withRoutes, request);

        assertThat(resolved).isEqualTo(new ClientIp("198.51.100.7", Source.FORWARDED_ABSENT));
        assertThat(output).doesNotContain("event=client_ip_absent");
        assertThat(absent("false")).isEqualTo(1);
        assertThat(absent("true")).isZero();
    }

    @Test
    void aMissingClientIpOnLivezWithTheKeyIsNotExpected(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = request("/livez", BffKeySlot.CURRENT);
        request.addHeader(FLY, "198.51.100.7");

        resolve(withRoutes, request);

        assertThat(output).doesNotContain("event=client_ip_absent");
        assertThat(absent("false")).isEqualTo(1);
    }

    @Test
    void aMissingClientIpElsewhereIsWarnedAsExpected(CapturedOutput output) throws Exception {
        MockHttpServletRequest lookup = request("/public/v/loja-exemplo/lookup", BffKeySlot.CURRENT);
        lookup.addHeader(FLY, "198.51.100.7");
        MockHttpServletRequest otherMethod = request("/public/v/loja-exemplo", BffKeySlot.CURRENT);
        otherMethod.setMethod("POST");
        otherMethod.addHeader(FLY, "198.51.100.7");

        assertThat(resolve(withRoutes, lookup).source()).isEqualTo(Source.FORWARDED_ABSENT);
        resolve(withRoutes, otherMethod);

        assertThat(output).contains("WARN").contains("event=client_ip_absent");
        assertThat(absent("true")).isEqualTo(2);
        assertThat(absent("false")).isZero();
    }

    @Test
    void matchingUsesTheNormalisedPathNotTheRawUri(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/public/v/loja-exemplo/lookup");
        request.setRemoteAddr("192.0.2.10");
        request.setAttribute(BffKeySlot.ATTRIBUTE, BffKeySlot.CURRENT);
        request.addHeader(FLY, "198.51.100.7");
        MockHttpServletRequest normalised = new MockHttpServletRequest("GET", "/public/v/loja-exemplo");
        request.setAttribute(PathFirewallFilter.REQUEST_PATH_ATTRIBUTE,
                ServletRequestPathUtils.parseAndCache(normalised));

        resolve(withRoutes, request);

        assertThat(absent("false")).as("decided on the cached path").isEqualTo(1);
    }

    @Test
    void anUntrustedClientHeaderIsLoggedAtInfoAndIgnored(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = request("/livez", BffKeySlot.NONE);
        request.addHeader(FLY, "198.51.100.7");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("X-Kybr-Client-IP", "203.0.113.9");

        assertThat(run(request)).isEqualTo(new ClientIp("198.51.100.7", Source.DIRECT));
        assertThat(output).contains("event=client_ip_untrusted").doesNotContain("203.0.113.9");
    }

    @Test
    void aMissingPlatformHeaderIsWarnedAndCounted(CapturedOutput output) throws Exception {
        assertThat(run(request("/v1/x", BffKeySlot.NONE))).isEqualTo(new ClientIp("192.0.2.10", Source.PLATFORM_ABSENT));

        assertThat(output).contains("WARN").contains("event=client_ip_platform_absent reason=missing");
        assertThat(count("kybr.client_ip.platform_absent", "reason", "missing")).isEqualTo(1);
    }

    @Test
    void aMissingPlatformHeaderOnLivezIsOnlyDebug(CapturedOutput output) throws Exception {
        run(request("/livez", BffKeySlot.NONE));

        assertThat(output).doesNotContain("event=client_ip_platform_absent");
        assertThat(count("kybr.client_ip.platform_absent", "reason", "missing")).isEqualTo(1);
    }

    @Test
    void anInvalidPlatformHeaderOnLivezIsStillWarned(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = request("/livez", BffKeySlot.NONE);
        request.addHeader(FLY, "not-an-ip");

        run(request);

        assertThat(output).contains("event=client_ip_platform_absent reason=invalid");
    }

    @Test
    void recordWorksForARequestTheBffFilterRejects() {
        MockHttpServletRequest request = request("/v1/x", BffKeySlot.NONE);
        request.addHeader(FLY, "198.51.100.7");
        request.addHeader("X-Kybr-Client-IP", "203.0.113.9");

        fly.record(request);

        assertThat(request.getAttribute(ClientIp.ATTRIBUTE)).isEqualTo(new ClientIp("198.51.100.7", Source.DIRECT));
        assertThat(MDC.get(ClientIpFilter.MDC_SOURCE)).isEqualTo("DIRECT");
    }

    @Test
    void runsRightAfterTheBffKeyFilter() {
        assertThat(fly.getOrder()).isEqualTo(FilterOrder.CLIENT_IP);
    }
}
