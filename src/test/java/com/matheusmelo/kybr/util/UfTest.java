package com.matheusmelo.kybr.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UfTest {

    @ParameterizedTest
    @ValueSource(strings = {"AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB",
        "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO"})
    void everyStateCodeIsValid(String uf) {
        assertThat(Uf.isValid(uf)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"XX", "BR", "sp", "S1", "SPP", "S", " SP", "ＳＰ"})
    void anythingElseIsNot(String uf) {
        assertThat(Uf.isValid(uf)).isFalse();
    }
}
