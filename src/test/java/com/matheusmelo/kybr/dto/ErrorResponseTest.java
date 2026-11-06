package com.matheusmelo.kybr.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The envelope is exactly {code, message} or {code, message, field}. */
class ErrorResponseTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void withoutFieldSerialisesCodeAndMessageOnly() {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(ErrorResponse.of(ErrorCode.ERRO_INTERNO)));

        assertThat(json.propertyNames()).containsExactly("code", "message");
        assertThat(json.get("code").asString()).isEqualTo("ERRO_INTERNO");
        assertThat(json.get("message").asString()).isEqualTo(ErrorCode.ERRO_INTERNO.message());
    }

    @Test
    void withFieldSerialisesTheThreeKeys() {
        JsonNode json = mapper.readTree(
                mapper.writeValueAsString(ErrorResponse.of(ErrorCode.VALOR_INVALIDO, "Idempotency-Key")));

        assertThat(json.propertyNames()).containsExactly("code", "message", "field");
        assertThat(json.get("field").asString()).isEqualTo("Idempotency-Key");
    }
}
