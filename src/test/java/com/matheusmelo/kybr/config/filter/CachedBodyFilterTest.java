package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.exception.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Filter [8] buffers POST/PUT JSON bodies (any spelling) and tees captured responses. */
class CachedBodyFilterTest {

    private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    private final CachedBodyFilter filter = new CachedBodyFilter();

    @ParameterizedTest
    @CsvSource({"POST, application/json", "PUT, application/json", "POST, application/json;charset=UTF-8",
        "POST, Application/JSON", "POST, application/problem+json", "POST, APPLICATION/JSON; charset=utf-8"})
    void buffersJsonBodiesSoTheyCanBeReadAgain(String method, String contentType) throws Exception {
        HttpServletRequest seen = run(limited(request(method, contentType)));

        assertThat(CachedBodyFilter.cachedBody(seen)).contains(BODY);
        assertThat(seen.getInputStream().readAllBytes()).isEqualTo(BODY);
        assertThat(seen.getInputStream().readAllBytes()).as("a second read").isEqualTo(BODY);
        assertThat(seen.getReader().readLine()).isEqualTo("{\"a\":1}");
    }

    @ParameterizedTest
    @CsvSource({"GET, application/json", "DELETE, application/json", "POST, text/plain",
        "POST, multipart/form-data; boundary=x", "POST, not a media type"})
    void leavesOtherRequestsUntouched(String method, String contentType) throws Exception {
        HttpServletRequest seen = run(limited(request(method, contentType)));

        assertThat(CachedBodyFilter.cachedBody(seen)).isEmpty();
        assertThat(seen.getInputStream().readAllBytes()).isEqualTo(BODY);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theResponseTeeCapturesOnlyWhenEnabledAndAlwaysPassesThrough(boolean capture) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> captured = new AtomicReference<>();

        filter.doFilter(limited(request("POST", "application/json")), response, (req, res) -> {
            CachedBodyFilter.CapturingResponse tee = CachedBodyFilter.capturingResponse((HttpServletResponse) res);
            if (capture) {
                tee.startCapture();
            }
            res.getOutputStream().write("{\"ok\":".getBytes(StandardCharsets.UTF_8));
            res.getWriter().write("true}");
            res.getWriter().flush();
            captured.set(tee.captured());
        });

        assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
        assertThat(captured.get()).isEqualTo(capture ? "{\"ok\":true}".getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    @Test
    void textWrittenThroughTheWriterReachesTheClientWithoutAnExplicitFlush() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding("UTF-8");

        filter.doFilter(request("GET", null), response, (req, res) -> res.getWriter().write("{\"ok\":true}"));

        assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
        assertThat(response.isCommitted()).as("outer filters can still reset it").isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aResetDiscardsTheCapturedCopySoTheReplayEqualsWhatTheClientGot(boolean fullReset) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding("UTF-8");
        AtomicReference<byte[]> captured = new AtomicReference<>();

        filter.doFilter(request("GET", null), response, (req, res) -> {
            CachedBodyFilter.CapturingResponse tee = CachedBodyFilter.capturingResponse((HttpServletResponse) res);
            tee.startCapture();
            res.getOutputStream().write("AAAA".getBytes(StandardCharsets.UTF_8));
            res.getWriter().write("CC");
            if (fullReset) {
                res.reset();
                res.setCharacterEncoding("UTF-8");
            } else {
                res.resetBuffer();
            }
            res.getOutputStream().write("BB".getBytes(StandardCharsets.UTF_8));
            captured.set(tee.captured());
        });

        assertThat(response.getContentAsString()).isEqualTo("BB");
        assertThat(captured.get()).isEqualTo("BB".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aNonBufferedRequestStillGetsTheResponseTee() throws Exception {
        filter.doFilter(request("GET", null), new MockHttpServletResponse(),
                (req, res) -> assertThat(CachedBodyFilter.capturingResponse((HttpServletResponse) res)).isNotNull());
    }

    @Test
    void aJsonBodyIsNeverBufferedWithoutTheBodyLimitWrapper() throws Exception {
        MockHttpServletRequest request = request("POST", "application/json");
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set((HttpServletRequest) req)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(seen.get()).as("chain must not run").isNull();
        assertThat(request.getInputStream().readAllBytes()).as("body left unread").isEqualTo(BODY);
    }

    @Test
    void bufferingStopsAtTheApplicableCap() throws Exception {
        MockHttpServletRequest request = request("POST", "application/json");
        request.setContent(new byte[2048]);
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

        assertThatThrownBy(() -> filter.doFilter(BodySizeLimitFilter.limited(request, 1024),
                new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req)))
                .isInstanceOf(PayloadTooLargeException.class);
        assertThat(seen.get()).isNull();
    }

    /** As filter [5] hands the request on: wrapped in the counting, capped stream. */
    private static HttpServletRequest limited(MockHttpServletRequest request) {
        return BodySizeLimitFilter.limited(request, 1024 * 1024);
    }

    private HttpServletRequest run(HttpServletRequest request) throws Exception {
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        return seen.get();
    }

    private static MockHttpServletRequest request(String method, String contentType) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/x");
        if (contentType != null) {
            request.setContentType(contentType);
        }
        request.setContent(BODY);
        return request;
    }
}
