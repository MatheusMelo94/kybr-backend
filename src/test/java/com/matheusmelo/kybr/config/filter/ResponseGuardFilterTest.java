package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class ResponseGuardFilterTest {

    static final Map<String, String> SECURITY_HEADERS = Map.of(
            "X-Content-Type-Options", "nosniff",
            "Cache-Control", "no-store",
            "Referrer-Policy", "no-referrer",
            "X-Frame-Options", "DENY",
            "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    static final String HSTS = "Strict-Transport-Security";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ResponseGuardFilter filter = filter("test");

    private ResponseGuardFilter filter(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new ResponseGuardFilter(new ErrorResponseWriter(mapper), environment);
    }

    private MockHttpServletResponse run(ResponseGuardFilter guard, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/x");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "11111111-1111-4111-8111-111111111111");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(RequestIdFilter.HEADER, "11111111-1111-4111-8111-111111111111");
        guard.doFilter(request, response, chain);
        return response;
    }

    @Test
    void setsTheSecurityHeadersBeforeTheChainRuns() throws Exception {
        MockHttpServletResponse response = run(filter, (req, res) -> {
            HttpServletResponse http = (HttpServletResponse) res;
            SECURITY_HEADERS.forEach((name, value) -> assertThat(http.getHeader(name)).isEqualTo(value));
        });

        SECURITY_HEADERS.forEach((name, value) -> assertThat(response.getHeaders(name)).containsExactly(value));
        assertThat(response.getHeader(HSTS)).isNull();
    }

    @Test
    void addsHstsInStagingAndProd() throws Exception {
        for (String profile : new String[] {"staging", "prod"}) {
            MockHttpServletResponse response = run(filter(profile), (req, res) -> { });

            assertThat(response.getHeaders(HSTS)).containsExactly("max-age=31536000; includeSubDomains");
        }
    }

    @Test
    void turnsSendRedirectIntoA500Envelope(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = run(filter, (req, res) ->
                ((HttpServletResponse) res).sendRedirect("https://evil.example/cases/secret-token"));

        assertBlockedRedirect(response);
        assertThat(output).contains("event=redirect_blocked").doesNotContain("secret-token");
    }

    @Test
    void turnsA3xxStatusWithLocationIntoA500Envelope(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = run(filter, (req, res) -> {
            HttpServletResponse http = (HttpServletResponse) res;
            http.setStatus(302);
            http.setHeader("Location", "/elsewhere");
            http.getOutputStream().write("moved".getBytes());
            http.flushBuffer();
        });

        assertBlockedRedirect(response);
        assertThat(output).contains("event=redirect_blocked");
    }

    private void assertBlockedRedirect(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getHeader("Location")).isNull();
        assertThat(mapper.readTree(response.getContentAsString()).get("code").asString()).isEqualTo("ERRO_INTERNO");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("11111111-1111-4111-8111-111111111111");
        SECURITY_HEADERS.forEach((name, value) -> assertThat(response.getHeaders(name)).containsExactly(value));
    }

    @Test
    void keepsALocationHeaderOnANonRedirectStatus() throws Exception {
        MockHttpServletResponse response = run(filter, (req, res) -> {
            HttpServletResponse http = (HttpServletResponse) res;
            http.setStatus(201);
            http.setHeader("Location", "/v1/things/1");
        });

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Location")).isEqualTo("/v1/things/1");
    }

    @Test
    void dropsCookiesAddedThroughTheApi(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = run(filter, (req, res) ->
                ((HttpServletResponse) res).addCookie(new Cookie("JSESSIONID", "abc")));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getCookies()).isEmpty();
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(output).contains("event=cookie_blocked");
    }

    @Test
    void dropsSetCookieHeadersInAnyCase(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = run(filter, (req, res) -> {
            HttpServletResponse http = (HttpServletResponse) res;
            http.setHeader("Set-Cookie", "a=1");
            http.addHeader("set-cookie", "b=2");
        });

        assertThat(response.getHeaderNames()).noneMatch(name -> name.equalsIgnoreCase("Set-Cookie"));
        assertThat(output).contains("event=cookie_blocked");
    }

    @Test
    void forbidsSessionCreation() throws Exception {
        run(filter, (req, res) -> {
            HttpServletRequest http = (HttpServletRequest) req;
            assertThatIllegalStateException().isThrownBy(http::getSession);
            assertThatIllegalStateException().isThrownBy(() -> http.getSession(true));
            assertThat(http.getSession(false)).isNull();
        });
    }

    @Test
    void runsOnTheErrorDispatchAfterTheRequestIdFilter() {
        assertThat(filter.getOrder()).isEqualTo(FilterOrder.RESPONSE_GUARD);
        assertThat(FilterOrder.runsOnErrorDispatch(filter.getOrder())).isTrue();
    }
}
