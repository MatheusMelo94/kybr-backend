package com.matheusmelo.kybr.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * CPF masking in free text. Vectors are synthetic: the acceptance vectors plus the boundary set
 * (a–f) below, inline until a shared {@code CpfFixtures} lands.
 */
class CpfMaskerTest {

    private static final String MASK = "***.***.***-**";

    static Stream<Arguments> maskedVectors() {
        return Stream.of(
                // acceptance
                Arguments.of("FULANO DE TAL 12345678909", "FULANO DE TAL " + MASK),
                Arguments.of("FULANO DE TAL 123.456.789-09", "FULANO DE TAL " + MASK),
                Arguments.of("FULANO DE TAL ***.456.789-**", "FULANO DE TAL " + MASK),
                Arguments.of("FULANO DE TAL 123.***.***-09", "FULANO DE TAL " + MASK),
                Arguments.of("FULANO DE TAL 123 456 789 09", "FULANO DE TAL " + MASK),
                // (b): start, end, parentheses, glued to letters on the left
                Arguments.of("12345678909 COMERCIO", MASK + " COMERCIO"),
                Arguments.of("COMERCIO 12345678909", "COMERCIO " + MASK),
                Arguments.of("FULANO (123.456.789-09) ME", "FULANO (" + MASK + ") ME"),
                Arguments.of("ABC12345678909", "ABC" + MASK),
                // (c): separators
                Arguments.of("FULANO 123-456-789-09", "FULANO " + MASK),
                Arguments.of("FULANO 123.456.789/09", "FULANO " + MASK),
                Arguments.of("FULANO 123 456 789 09", "FULANO " + MASK),
                Arguments.of("FULANO 123\t456\t789\t09", "FULANO " + MASK),
                // (e): accepted over-mask of an 11-digit phone-like run
                Arguments.of("LOJA FONE 11987654321", "LOJA FONE " + MASK),
                // two runs in one string
                Arguments.of("A 12345678909 E B 98765432100", "A " + MASK + " E B " + MASK));
    }

    static Stream<String> unchangedVectors() {
        return Stream.of(
                "EMPRESA 12345678000195 LTDA", // a valid numeric CNPJ
                "LOJA 12345678901A64 LTDA", // (a): a valid alphanumeric CNPJ starting with 11 digits
                "12345678901A64", // the same CNPJ alone
                "LOJA 12ABC34501DE35 LTDA", // another valid alphanumeric CNPJ
                "TEL 1234567890", // (d): 10 digits
                "COD 123456789012", // (d): 12 digits
                "EMPRESA 12345678000196 LTDA", // 14 digits, not a valid CNPJ: no 11-digit run inside
                "FULANO DE TAL " + MASK, // already masked
                "PADARIA SAO JOAO LTDA",
                "");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("maskedVectors")
    void masksCpfShapedRuns(String input, String expected) {
        assertThat(CpfMasker.mask(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unchangedVectors")
    void leavesEverythingElseUnchanged(String input) {
        assertThat(CpfMasker.mask(input)).isEqualTo(input);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allInputs")
    void isIdempotent(String input) {
        String once = CpfMasker.mask(input);
        assertThat(CpfMasker.mask(once)).isEqualTo(once);
    }

    @Test
    void isNullSafe() {
        assertThat(CpfMasker.mask(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678909", "123.456.789-09"})
    void neverLeavesAnyCpfDigit(String cpf) {
        assertThat(CpfMasker.mask("X " + cpf + " Y")).doesNotContainPattern("[0-9]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABC12345678909", "12345678909ab", "8b5d-a71416362082", "8b5d-71416362082d"})
    void theBoundedRuleSkipsRunsGluedToLettersThatTheRazaoSocialRuleMasks(String input) {
        assertThat(CpfMasker.mask(input)).contains(MASK);
        assertThat(CpfMasker.maskBounded(input)).isEqualTo(input);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"12345678909", "X 123.456.789-09 Y", "cpf=***.456.789-**", "(123 456 789 09)"})
    void theBoundedRuleMasksRunsBetweenNonAlphanumerics(String input) {
        assertThat(CpfMasker.maskBounded(input)).isEqualTo(CpfMasker.mask(input)).contains(MASK);
        assertThat(CpfMasker.maskBounded(null)).isNull();
    }

    static Stream<String> allInputs() {
        return Stream.concat(maskedVectors().map(a -> (String) a.get()[0]), unchangedVectors());
    }
}
