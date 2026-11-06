package com.matheusmelo.kybr.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Filters write the envelope to the raw response, outside MVC. */
class ErrorResponseWriterTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ErrorResponseWriter writer = new ErrorResponseWriter(mapper);

    @Test
    void writesStatusContentTypeAndEnvelopeForACode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ErrorCode.BFF_KEY_AUSENTE);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactly("code", "message");
        assertThat(body.get("code").asString()).isEqualTo("BFF_KEY_AUSENTE");
    }

    @Test
    void writesAnExceptionWithItsHeaders() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, new RateLimitExceededException(ErrorCode.LIMITE_TENTATIVAS, 42));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("42");
        assertThat(mapper.readTree(response.getContentAsString()).get("code").asString())
                .isEqualTo("LIMITE_TENTATIVAS");
    }

    @Test
    void writesAnExplicitStatusAndField() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, HttpStatus.BAD_REQUEST, ErrorCode.CNPJ_INVALIDO, "cnpj", Map.of());

        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertThat(body.get("field").asString()).isEqualTo("cnpj");
    }

    @Test
    void subclassesCarryTheirCodesFieldsAndHeaders() {
        assertThat(new IdempotencyMismatchException()).satisfies(e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.VALOR_INVALIDO);
            assertThat(e.field()).isEqualTo("Idempotency-Key");
        });
        assertThat(new IdempotencyInProgressException()).satisfies(e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INDISPONIVEL);
            assertThat(e.headers()).containsExactly(Map.entry("Retry-After", "1"));
        });
        assertThat(new ServiceUnavailableException(new RuntimeException("synthetic")).code())
                .isEqualTo(ErrorCode.INDISPONIVEL);
        assertThat(new PayloadTooLargeException().code()).isEqualTo(ErrorCode.REQUISICAO_GRANDE);
        assertThat(new ApiException(ErrorCode.RECURSO_NAO_ENCONTRADO).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void retryAfterIsDigitsOnlyAndAtLeastOne() {
        assertThat(new RateLimitExceededException(ErrorCode.LIMITE_CNPJ, 0).headers().get("Retry-After"))
                .isEqualTo("1");
        assertThat(new RateLimitExceededException(ErrorCode.LIMITE_CNPJ, 3600).headers().get("Retry-After"))
                .matches("^\\d+$");
    }

    @Test
    void rateLimitExceptionRequiresA429Code() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitExceededException(ErrorCode.VALOR_INVALIDO, 10));
    }

    @Test
    void exceptionMessagesCarryNoInput() {
        assertThat(new ApiException(ErrorCode.CNPJ_INVALIDO, "cnpj").getMessage()).isEqualTo("CNPJ_INVALIDO");
    }
}
