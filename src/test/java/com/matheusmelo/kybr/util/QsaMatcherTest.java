package com.matheusmelo.kybr.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.util.QsaMatcher.Match;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QsaMatcherTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "JOSÉ  da Silva | jose da silva",
        "  Ana   Maria  | ANA MARIA",
        "D'Ávila-Neto Jr. | DAVILANETO JR",
        "Ｍａｒｉａ Souza | MARIA SOUZA",
        "Conceição Araújo | CONCEICAO ARAUJO"})
    void normalisedNamesAreEqual(String typed, String registered) {
        assertThat(NameNormalizer.normalize(typed)).isEqualTo(NameNormalizer.normalize(registered));
    }

    @Test
    void normalisationFoldsAccentsCaseSeparatorsAndSpaces() {
        assertThat(NameNormalizer.normalize(" José  d'Ávila-Souza. ")).isEqualTo("JOSE DAVILASOUZA");
        assertThat(NameNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void equalNamesAfterNormalisationMatchExactly() {
        assertThat(QsaMatcher.match("JOSÉ  da Silva", List.of("OUTRO SOCIO", "jose da silva"))).isEqualTo(Match.EXACT);
    }

    @Test
    void everyTokenOfTheShorterNameInTheOtherIsPartial() {
        assertThat(QsaMatcher.match("Maria Souza", List.of("MARIA APARECIDA SOUZA"))).isEqualTo(Match.PARTIAL);
        assertThat(QsaMatcher.match("Maria Aparecida Souza", List.of("MARIA SOUZA"))).isEqualTo(Match.PARTIAL);
    }

    @Test
    void sameFirstAndLastTokensArePartial() {
        assertThat(QsaMatcher.match("Carlos Henrique Souza", List.of("CARLOS ALBERTO SOUZA"))).isEqualTo(Match.PARTIAL);
    }

    @Test
    void otherwiseNoneMatches() {
        assertThat(QsaMatcher.match("Pedro Lima", List.of("ANA LIMA"))).isEqualTo(Match.NONE);
        assertThat(QsaMatcher.match("Pedro Lima", List.of())).isEqualTo(Match.NONE);
    }

    @Test
    void theBestMatchAcrossPartnersWins() {
        assertThat(QsaMatcher.match("Maria Souza", List.of("ANA LIMA", "MARIA APARECIDA SOUZA", "maria souza")))
                .isEqualTo(Match.EXACT);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "JOAO CARLOS SILVA ***.***.***-** | João Carlos Silva | EXACT",
        "JOAO CARLOS SILVA 12345678909 | João Carlos Silva | EXACT",
        "JOAO CARLOS SILVA 123.456.789-09 | joao carlos silva | EXACT",
        "JOAO CARLOS SILVA ***.***.***-** | João Silva | PARTIAL",
        "JOAO CARLOS SILVA ***.***.***-** | Pedro Lima | NONE"})
    void aMicroEntrepreneurIsComparedWithTheNameInTheCompanyName(String razaoSocial, String typed, Match expected) {
        assertThat(QsaMatcher.matchMei(typed, razaoSocial)).isEqualTo(expected);
    }
}
