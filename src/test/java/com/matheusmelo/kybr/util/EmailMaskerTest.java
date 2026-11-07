package com.matheusmelo.kybr.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailMaskerTest {

    @Test
    void keepsTheFirstCharacterOfTheLocalPartAndTheWholeDomain() {
        assertThat(EmailMasker.mask("joao@empresa.com.br")).isEqualTo("j•••@empresa.com.br");
    }

    @Test
    void aOneCharacterLocalPartMasksToThreeBulletsOnly() {
        assertThat(EmailMasker.mask("a@x.test")).isEqualTo("•••@x.test");
    }

    @Test
    void hidesTheLengthOfALongLocalPart() {
        assertThat(EmailMasker.mask("maria.aparecida.exemplo@x.test")).isEqualTo("m•••@x.test");
    }

    @Test
    void anAstralFirstCharacterIsKeptWhole() {
        assertThat(EmailMasker.mask("𝐀bc@x.test")).isEqualTo("𝐀•••@x.test");
    }

    @Test
    void splitsOnTheLastAtSign() {
        assertThat(EmailMasker.mask("\"a@b\"@x.test")).isEqualTo("\"•••@x.test");
    }

    @Test
    void withoutAnAtSignNothingIsShown() {
        assertThat(EmailMasker.mask("not-an-address")).isEqualTo("•••");
    }

    @Test
    void nullStaysNull() {
        assertThat(EmailMasker.mask(null)).isNull();
    }
}
