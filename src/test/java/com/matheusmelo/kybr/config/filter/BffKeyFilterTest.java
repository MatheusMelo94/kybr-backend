package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties.Bff;
import com.matheusmelo.kybr.config.KybrProperties.Route;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import com.matheusmelo.kybr.model.BffKeySlot;
import com.matheusmelo.kybr.service.BffKeyVerifier;
import com.matheusmelo.kybr.service.ClientIpResolver;
import com.matheusmelo.kybr.support.TestSecrets;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class BffKeyFilterTest {

    private static final String CURRENT = TestSecrets.BFF_KEY_CURRENT;
    private static final String PREVIOUS = TestSecrets.BFF_KEY_PREVIOUS;
    private static final String DOCUMENT_UPLOAD = "/public/v/{slug}/cases/{token}/documents";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Bff bff = new Bff(CURRENT, PREVIOUS, List.of(
            new Route(HttpMethod.GET, "/livez"),
            new Route(HttpMethod.POST, DOCUMENT_UPLOAD),
            new Route(HttpMethod.OPTIONS, DOCUMENT_UPLOAD)));
    private final BffKeyFilter filter = new BffKeyFilter(bff, new BffKeyVerifier(bff),
            new ErrorResponseWriter(JsonMapper.builder().build()), meters,
            new ClientIpFilter(new ClientIpResolver((String) null), meters));

    private record Outcome(MockHttpServletResponse response, HttpServletRequest downstream) {
        boolean passed() {
            return downstream != null;
        }
    }

    private Outcome send(String method, String path, String... keys) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        for (String key : keys) {
            request.addHeader(BffKeyFilter.HEADER, key);
        }
        request.setAttribute(PathFirewallFilter.REQUEST_PATH_ATTRIBUTE, ServletRequestPathUtils.parseAndCache(request));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> downstream.set((HttpServletRequest) req));
        return new Outcome(response, downstream.get());
    }

    private double rejected(String reason) {
        var counter = meters.find("kybr.bff_key.rejected").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private double accepted(String slot) {
        var counter = meters.find("kybr.bff_key.accepted").tag("slot", slot).counter();
        return counter == null ? 0 : counter.count();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/x", "/public/v/s", "/webhooks/x", "/not/mapped/anywhere"})
    void missingAndInvalidKeysGetTheSame403Everywhere(String path, CapturedOutput output) throws Exception {
        Outcome missing = send("GET", path);
        Outcome invalid = send("GET", path, "not-the-key");

        assertThat(missing.passed()).isFalse();
        assertThat(invalid.passed()).isFalse();
        assertThat(missing.response().getStatus()).isEqualTo(403);
        assertThat(missing.response().getContentAsString())
                .contains("BFF_KEY_AUSENTE")
                .isEqualTo(invalid.response().getContentAsString());
        assertThat(rejected("missing")).isEqualTo(1);
        assertThat(rejected("invalid")).isEqualTo(1);
        assertThat(output).contains("event=bff_key_rejected reason=missing")
                .contains("event=bff_key_rejected reason=invalid")
                .doesNotContain("not-the-key");
    }

    @ParameterizedTest
    @CsvSource({"POST, /public/v/x/cases/y/documents", "OPTIONS, /public/v/x/cases/y/documents", "GET, /livez"})
    void theExemptRoutesPassWithoutAKey(String method, String path) throws Exception {
        Outcome outcome = send(method, path);

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.downstream().getAttribute(BffKeySlot.ATTRIBUTE)).isEqualTo(BffKeySlot.NONE);
    }

    @ParameterizedTest
    @CsvSource({"GET, /public/v/x/cases/y/documents", "GET, /livez/", "POST, /public/v/x/cases/y/documents/",
        "POST, /livez", "GET, /livez/x"})
    void nearMissesOfAnExemptionNeedTheKey(String method, String path) throws Exception {
        Outcome outcome = send(method, path);

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(403);
    }

    @Test
    void anExemptRouteStillRecordsAValidKeyAndIgnoresAnInvalidOne() throws Exception {
        assertThat(send("GET", "/livez", CURRENT).downstream().getAttribute(BffKeySlot.ATTRIBUTE))
                .isEqualTo(BffKeySlot.CURRENT);
        assertThat(send("GET", "/livez", "not-the-key").downstream().getAttribute(BffKeySlot.ATTRIBUTE))
                .isEqualTo(BffKeySlot.NONE);
    }

    @Test
    void theCurrentAndPreviousKeysPassWithTheirSlot() throws Exception {
        Outcome current = send("GET", "/v1/x", CURRENT);
        Outcome previous = send("GET", "/v1/x", PREVIOUS);

        assertThat(current.downstream().getAttribute(BffKeySlot.ATTRIBUTE)).isEqualTo(BffKeySlot.CURRENT);
        assertThat(previous.downstream().getAttribute(BffKeySlot.ATTRIBUTE)).isEqualTo(BffKeySlot.PREVIOUS);
        assertThat(accepted("CURRENT")).isEqualTo(1);
        assertThat(accepted("PREVIOUS")).isEqualTo(1);
    }

    @Test
    void theValueIsCaseSensitiveAndUntrimmed() throws Exception {
        // A dedicated synthetic key that starts with a letter, so the case flip always differs
        // (the shared test keys are random and may start with a digit, '-' or '_').
        String key = "Kybr" + TestSecrets.base64Url(32, 7).substring(4);
        Bff lettered = new Bff(key, null, List.of());
        BffKeyFilter strict = new BffKeyFilter(lettered, new BffKeyVerifier(lettered),
                new ErrorResponseWriter(JsonMapper.builder().build()), meters,
                new ClientIpFilter(new ClientIpResolver((String) null), meters));
        String flipped = "k" + key.substring(1);

        assertThat(passes(strict, key)).isTrue();
        assertThat(passes(strict, flipped)).isFalse();
        assertThat(passes(strict, key + " ")).isFalse();
        assertThat(passes(strict, " " + key)).isFalse();
    }

    private static boolean passes(BffKeyFilter target, String key) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/x");
        request.addHeader(BffKeyFilter.HEADER, key);
        request.setAttribute(PathFirewallFilter.REQUEST_PATH_ATTRIBUTE, ServletRequestPathUtils.parseAndCache(request));
        AtomicReference<Boolean> passed = new AtomicReference<>(false);
        target.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(true));
        return passed.get();
    }

    @Test
    void moreThanOneValueIsRejectedAsMultiple(CapturedOutput output) throws Exception {
        String body = send("GET", "/v1/x").response().getContentAsString();

        for (Outcome outcome : List.of(
                send("GET", "/v1/x", CURRENT, "not-the-key"),
                send("GET", "/v1/x", "not-the-key", CURRENT),
                send("GET", "/v1/x", CURRENT, CURRENT),
                send("GET", "/v1/x", CURRENT + "," + PREVIOUS))) {
            assertThat(outcome.passed()).isFalse();
            assertThat(outcome.response().getStatus()).isEqualTo(403);
            assertThat(outcome.response().getContentAsString()).isEqualTo(body);
        }
        assertThat(rejected("multiple")).isEqualTo(4);
        assertThat(output).contains("event=bff_key_rejected reason=multiple");
    }

    @Test
    void theRejectionReasonIsLocaleIndependent(CapturedOutput output) throws Exception {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            send("GET", "/v1/x", "not-the-key");
        } finally {
            Locale.setDefault(original);
        }

        assertThat(rejected("invalid")).isEqualTo(1);
        assertThat(output).contains("event=bff_key_rejected reason=invalid");
    }

    @Test
    void theKeyIsHiddenDownstreamAndNeverLogged(CapturedOutput output) throws Exception {
        HttpServletRequest downstream = send("GET", "/v1/x", CURRENT).downstream();

        assertThat(downstream.getHeader("X-Kybr-BFF-Key")).isNull();
        assertThat(downstream.getHeader("x-kybr-bff-key")).isNull();
        assertThat(Collections.list(downstream.getHeaders("X-KYBR-BFF-KEY"))).isEmpty();
        assertThat(Collections.list(downstream.getHeaderNames())).noneMatch("X-Kybr-BFF-Key"::equalsIgnoreCase);
        assertThat(output).doesNotContain(CURRENT);
    }

    @Test
    void runsAfterThePathFirewallAndTheBodyLimit() {
        assertThat(filter.getOrder()).isEqualTo(FilterOrder.BFF_KEY);
    }
}
