package com.matheusmelo.kybr.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** CNPJ vectors, including length and character boundaries. All values are synthetic. */
class CnpjTest {

    @ParameterizedTest(name = "{0} is a valid canonical CNPJ")
    @ValueSource(strings = {"12345678000195", "98765432000198", "11222333000181", "12ABC34501DE35"})
    void acceptsTheApprovedVectors(String cnpj) {
        assertThat(Cnpj.isValidCanonical(cnpj)).isTrue();
    }

    @ParameterizedTest(name = "{0} is rejected: {1}")
    @CsvSource(delimiter = '|', textBlock = """
            12ABC34501DE36     | wrong check digit (expected 35)
            12ABC34501DE3A     | letter in a check-digit position
            00000000000000     | all characters the same (its check digits compute correctly)
            11111111111111     | all characters the same
            12abc34501de35     | lowercase is not canonical
            12.345.678/0001-95 | punctuated is not canonical
            1234567800019      | 13 characters
            123456780001950    | 15 characters
            ' 12345678000195'  | leading space
            '12345678000195 '  | trailing space
            １２３４５６７８０００１９５ | full-width digits
            ١٢٣٤٥٦٧٨٠٠٠١٩٥     | Arabic-Indic digits
            12АBC34501DE35     | Cyrillic A in an alphanumeric position
            """)
    void rejectsInvalidVectors(String cnpj, String reason) {
        assertThat(Cnpj.isValidCanonical(cnpj)).as(reason).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsNullAndEmpty(String cnpj) {
        assertThat(Cnpj.isValidCanonical(cnpj)).isFalse();
    }

    @ParameterizedTest(name = "canonicalize({0}) = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            12abc34501de35        | 12ABC34501DE35
            12.345.678/0001-95    | 12345678000195
            ' 12.345.678/0001-95 '| 12345678000195
            12ABC34501DE35        | 12ABC34501DE35
            """)
    void canonicalizesNonWireInput(String input, String expected) {
        assertThat(Cnpj.canonicalize(input)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12ABC34501DE36", "00.000.000/0000-00", "not a cnpj", ""})
    void canonicalizeIsEmptyWhenTheResultIsInvalid(String input) {
        assertThat(Cnpj.canonicalize(input)).isEmpty();
    }

    @Test
    void canonicalizeOfNullIsEmpty() {
        assertThat(Cnpj.canonicalize(null)).isEqualTo(Optional.empty());
    }

    @Test
    void masksANumericCnpj() {
        assertThat(Cnpj.mask("12345678000195")).isEqualTo("12.345.678/0001-95");
    }

    @Test
    void masksAnAlphanumericCnpjKeepingItsLetters() {
        assertThat(Cnpj.mask("12ABC34501DE35")).isEqualTo("12.ABC.345/01DE-35");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12.345.678/0001-95", "12ABC34501DE36"})
    void maskRefusesAnythingButAValidCanonicalCnpj(String input) {
        assertThatThrownBy(() -> Cnpj.mask(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(input);
    }
}
