package com.matheusmelo.kybr.model.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Audit details are a closed set of fields that only ever hold masked personal values. */
class ResumoAuditoriaTest {

    @Test
    void maskedValuesAreAccepted() {
        assertThat(ResumoAuditoria.ofCnpj("11.***.***/0001-**").cnpjMascarado()).isEqualTo("11.***.***/0001-**");
        assertThat(ResumoAuditoria.ofContato("m•••@example.com", "•••@example.org").novoMascarado())
                .isEqualTo("•••@example.org");
    }

    @ParameterizedTest
    @ValueSource(strings = {"11222333000181", "11.222.333/0001-81", "maria@example.com", "ma•••@example.com",
        "plain text"})
    void anUnmaskedValueIsRefused(String value) {
        assertThatThrownBy(() -> ResumoAuditoria.ofCnpj(value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(value);
        assertThatThrownBy(() -> ResumoAuditoria.ofContato(null, value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thereIsNoFreeFormMap() {
        assertThat(Arrays.stream(ResumoAuditoria.class.getRecordComponents()).map(RecordComponent::getType))
                .noneMatch(Map.class::isAssignableFrom);
    }
}
