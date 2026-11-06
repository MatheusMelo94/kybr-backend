package com.matheusmelo.kybr.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GlobalExceptionHandlerTest {

    @ParameterizedTest
    @CsvSource({
        "displayName, display_name",
        "cnpj, cnpj",
        "nested.shortCode, nested.short_code",
        "items[0].unitPrice, items[0].unit_price",
        "socios[2], socios[2]"
    })
    void translatesJavaPropertyPathsToSnakeCase(String javaPath, String wireName) {
        assertThat(GlobalExceptionHandler.wireFieldName(javaPath)).isEqualTo(wireName);
    }

    @ParameterizedTest
    @CsvSource({
        "CNPJ_INVALIDO, CNPJ_INVALIDO",
        "{jakarta.validation.constraints.NotBlank.message}, VALOR_INVALIDO",
        "must not be blank, VALOR_INVALIDO",
        "NOT_A_CODE, VALOR_INVALIDO"
    })
    void aConstraintWhoseMessageTemplateIsACodeNameRaisesThatCode(String template, ErrorCode expected) {
        assertThat(GlobalExceptionHandler.codeForTemplate(template)).isEqualTo(expected);
    }
}
