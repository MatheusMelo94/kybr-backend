package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.RequestPath;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.json.JsonMapper;

class PathFirewallFilterTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final PathFirewallFilter filter = new PathFirewallFilter(new ErrorResponseWriter(mapper));

    private static MockHttpServletRequest request(String rawUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", rawUri);
        request.setServletPath(rawUri);
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"//livez", "/v1;jsessionid=x/a", "/a%2Fb", "/a%5Cb", "/a/%2e%2e/b", "/a/./b",
        "/a/../b", "/a\u0001b", "/a\\b", "/public/v/x/cases/y%2Fdocuments", "/public/v/x/%2e%2e/cases/y/documents"})
    void rejectsANonNormalisedPathWith400BeforeTheChain(String rawUri) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainRan = new AtomicBoolean();

        filter.doFilter(request(rawUri), response, (req, res) -> chainRan.set(true));

        assertThat(chainRan).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(mapper.readTree(response.getContentAsString()).get("code").asString())
                .isEqualTo("REQUISICAO_INVALIDA");
    }

    @Test
    void passesANormalPathAndCachesItsParsedRequestPath() throws Exception {
        MockHttpServletRequest request = request("/public/v/slug/cases/token/documents");
        AtomicBoolean chainRan = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            chainRan.set(true);
            RequestPath path = PathFirewallFilter.requestPath((jakarta.servlet.http.HttpServletRequest) req);
            assertThat(path.pathWithinApplication().value()).isEqualTo("/public/v/slug/cases/token/documents");
            assertThat(ServletRequestPathUtils.hasParsedRequestPath(req)).isTrue();
        });

        assertThat(chainRan).isTrue();
    }

    @Test
    void sitsAfterTheOuterFiltersAndOnlyOnTheRequestDispatch() {
        assertThat(filter.getOrder()).isEqualTo(FilterOrder.PATH_FIREWALL);
        assertThat(FilterOrder.runsOnErrorDispatch(filter.getOrder())).isFalse();
    }
}
