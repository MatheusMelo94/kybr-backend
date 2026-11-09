package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class D6CorsFilterTest {

    static final String ALLOWED = "https://kybr.com.br";
    static final String PREVIEW = "https://kybr-web-git-feat-x-kybr.vercel.app";
    static final String UPLOAD = "/public/v/loja-1/cases/AAAAAAAAAAAAAAAAAAAAAA/documents";

    private final D6CorsFilter filter = new D6CorsFilter(
            new KybrProperties.D6Cors(List.of(ALLOWED), List.of("^https://kybr-web-[a-z0-9-]+-kybr\\.vercel\\.app$")),
            false);
    private final AtomicInteger chainCalls = new AtomicInteger();

    private MockHttpServletResponse run(MockHttpServletRequest request, int status) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            chainCalls.incrementAndGet();
            ((HttpServletResponse) res).setStatus(status);
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest post(String uri, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    private static MockHttpServletRequest preflight(String origin, String method, String headers) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", UPLOAD);
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", method);
        if (headers != null) {
            request.addHeader("Access-Control-Request-Headers", headers);
        }
        return request;
    }

    static void assertAllowed(MockHttpServletResponse response, String origin) {
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        assertThat(response.getHeader("Access-Control-Expose-Headers")).isEqualTo("Retry-After, X-Request-Id");
        assertThat(response.getHeaders("Vary")).contains("Origin");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    static void assertNoCors(MockHttpServletResponse response) {
        assertThat(response.getHeaderNames()).noneMatch(name -> name.startsWith("Access-Control-"));
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 400, 404, 409, 413, 415, 429, 500, 503})
    void anAllowedOriginGetsTheHeadersWhateverTheStatus(int status) throws Exception {
        MockHttpServletResponse response = run(post(UPLOAD, ALLOWED), status);

        assertThat(response.getStatus()).isEqualTo(status);
        assertAllowed(response, ALLOWED);
    }

    @Test
    void thePreviewPatternIsAnAllowedOrigin() throws Exception {
        assertAllowed(run(post(UPLOAD, PREVIEW), 201), PREVIEW);
    }

    @Test
    void theHeadersSurviveAResponseResetByALaterFilter() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(post(UPLOAD, ALLOWED), response, (req, res) -> {
            res.reset();
            ((HttpServletResponse) res).setStatus(500);
        });

        assertThat(response.getStatus()).isEqualTo(500);
        assertAllowed(response, ALLOWED);
    }

    @Test
    void theErrorDispatchRestoresTheHeadersTheContainerCleared() throws Exception {
        MockHttpServletRequest request = post(UPLOAD, ALLOWED);
        run(request, 413);

        MockHttpServletRequest error = post("/error", ALLOWED);
        error.setDispatcherType(DispatcherType.ERROR);
        error.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, UPLOAD);
        MockHttpServletResponse response = run(error, 413);

        assertAllowed(response, ALLOWED);
    }

    @Test
    void anErrorDispatchForARequestTheFilterNeverSawStillMatchesTheOriginalUri() throws Exception {
        MockHttpServletRequest error = post("/error", ALLOWED);
        error.setDispatcherType(DispatcherType.ERROR);
        error.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, UPLOAD);

        assertAllowed(run(error, 400), ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "https://kybr.com.br.evil.example", "http://kybr.com.br", "null",
        "https://kybr-web-x-kybr.vercel.app.evil.example"})
    void aDisallowedOriginIsProcessedWithoutAllowHeaders(String origin) throws Exception {
        MockHttpServletResponse response = run(post(UPLOAD, origin), 201);

        assertThat(chainCalls).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeaders("Vary")).contains("Origin");
        assertNoCors(response);
    }

    @Test
    void aMissingOriginIsProcessedWithVaryOnly() throws Exception {
        MockHttpServletResponse response = run(post(UPLOAD, null), 201);

        assertThat(chainCalls).hasValue(1);
        assertThat(response.getHeaders("Vary")).contains("Origin");
        assertNoCors(response);
    }

    @Test
    void anAllowedPreflightIs204WithTheAllowHeadersAndNeverReachesTheChain() throws Exception {
        MockHttpServletResponse response = run(preflight(ALLOWED, "POST", "content-type"), 200);

        assertThat(chainCalls).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(204);
        assertAllowed(response, ALLOWED);
        assertThat(response.getHeader("Access-Control-Allow-Methods")).isEqualTo("POST, OPTIONS");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).isEqualTo("Content-Type");
        assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("600");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void aPreflightWithoutRequestedHeadersIsAllowed() throws Exception {
        MockHttpServletResponse response = run(preflight(ALLOWED, "POST", null), 200);

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader("Access-Control-Allow-Methods")).isEqualTo("POST, OPTIONS");
    }

    @Test
    void aDisallowedPreflightIs204WithoutAllowHeaders() throws Exception {
        MockHttpServletResponse response = run(preflight("https://evil.example", "POST", "content-type"), 200);

        assertThat(chainCalls).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeaders("Vary")).contains("Origin");
        assertNoCors(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE", "GET"})
    void aPreflightForAnotherMethodGetsNoAllowHeaders(String method) throws Exception {
        MockHttpServletResponse response = run(preflight(ALLOWED, method, null), 200);

        assertThat(response.getStatus()).isEqualTo(204);
        assertNoCors(response);
    }

    @Test
    void aPreflightAskingForAnotherHeaderGetsNoAllowHeaders() throws Exception {
        MockHttpServletResponse response = run(preflight(ALLOWED, "POST", "content-type, authorization"), 200);

        assertThat(response.getStatus()).isEqualTo(204);
        assertNoCors(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/public/v/loja-1/cases/AAAAAAAAAAAAAAAAAAAAAA/documents/",
        "/public/v/loja-1/cases/AAAAAAAAAAAAAAAAAAAAAA/documents;x",
        "/public/v/loja-1//cases/AAAAAAAAAAAAAAAAAAAAAA/documents",
        "/public/v/Loja/cases/AAAAAAAAAAAAAAAAAAAAAA/documents",
        "/public/v/loja-1/cases/short/documents",
        "/public/v/loja-1/cases/AAAAAAAAAAAAAAAAAAAAAA%2Fx/documents",
        "/public/v/loja-1/cases/AAAAAAAAAAAAAAAAAAAAAA",
        "/public/v/loja-1",
        "/v1/anything"})
    void noOtherPathGetsCorsHeaders(String uri) throws Exception {
        MockHttpServletResponse response = run(post(uri, ALLOWED), 200);

        assertThat(chainCalls).hasValue(1);
        assertNoCors(response);
        assertThat(response.getHeaders("Vary")).doesNotContain("Origin");
    }

    @Test
    void aPreflightOnAnotherPathPassesThrough() throws Exception {
        MockHttpServletRequest request = preflight(ALLOWED, "POST", null);
        request.setRequestURI("/public/v/loja-1");

        MockHttpServletResponse response = run(request, 403);

        assertThat(chainCalls).hasValue(1);
        assertNoCors(response);
    }

    @Test
    void runsRightAfterTheRequestIdAndOnTheErrorDispatch() {
        assertThat(D6CorsFilter.ORDER).isGreaterThan(FilterOrder.REQUEST_ID).isLessThan(FilterOrder.RESPONSE_GUARD);
        assertThat(FilterOrder.runsOnErrorDispatch(D6CorsFilter.ORDER)).isTrue();
    }
}
