package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.json.JsonMapper;

class SecurityConfigTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SecurityConfig config = new SecurityConfig();
    private final ErrorResponseWriter writer = new ErrorResponseWriter(mapper);

    @Test
    void theEntryPointWritesA401SessionEnvelopeWithoutAChallenge() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        config.authenticationEntryPoint(writer)
                .commence(new MockHttpServletRequest(), response, new InsufficientAuthenticationException("x"));

        assertEnvelope(response, 401, "SESSAO_EXPIRADA");
    }

    @Test
    void accessDeniedLooksLikeAnUnknownRoute() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        config.accessDeniedHandler(writer)
                .handle(new MockHttpServletRequest(), response, new AccessDeniedException("x"));

        assertEnvelope(response, 404, "RECURSO_NAO_ENCONTRADO");
    }

    private void assertEnvelope(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        assertThat(mapper.readTree(response.getContentAsString()).get("code").asString()).isEqualTo(code);
    }
}
