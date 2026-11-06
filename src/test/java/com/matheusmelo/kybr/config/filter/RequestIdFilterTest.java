package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    static final Pattern UUID_V4 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void setsAUuidV4HeaderAndMdcBeforeTheChainRuns() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> headerSeenByChain = new AtomicReference<>();
        AtomicReference<String> mdcSeenByChain = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                    jakarta.servlet.http.HttpServletResponse res) {
                headerSeenByChain.set(res.getHeader(RequestIdFilter.HEADER));
                mdcSeenByChain.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        }));

        String id = response.getHeader(RequestIdFilter.HEADER);
        assertThat(id).matches(UUID_V4);
        assertThat(headerSeenByChain.get()).isEqualTo(id);
        assertThat(mdcSeenByChain.get()).isEqualTo(id);
    }

    @Test
    void neverEchoesAnInboundRequestId() throws Exception {
        String inbound = "00000000-0000-4000-8000-000000000001";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/x");
        request.addHeader(RequestIdFilter.HEADER, inbound);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches(UUID_V4).isNotEqualTo(inbound);
    }

    @Test
    void eachRequestGetsADifferentId() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), first, new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest(), second, new MockFilterChain());

        assertThat(first.getHeader(RequestIdFilter.HEADER)).isNotEqualTo(second.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void clearsMdcAfterTheRequestEvenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches(UUID_V4);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void clearsMdcAfterASuccessfulRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void theErrorDispatchReusesTheOriginalRequestsId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/x");
        MockHttpServletResponse original = new MockHttpServletResponse();
        filter.doFilter(request, original, new MockFilterChain());

        request.setDispatcherType(DispatcherType.ERROR);
        MockHttpServletResponse errorResponse = new MockHttpServletResponse();
        filter.doFilter(request, errorResponse, new MockFilterChain());

        assertThat(errorResponse.getHeader(RequestIdFilter.HEADER))
                .isEqualTo(original.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void runsOnTheErrorDispatch() {
        assertThat(FilterOrder.runsOnErrorDispatch(filter.getOrder())).isTrue();
        assertThat(filter.getOrder()).isEqualTo(FilterOrder.REQUEST_ID);
    }
}
