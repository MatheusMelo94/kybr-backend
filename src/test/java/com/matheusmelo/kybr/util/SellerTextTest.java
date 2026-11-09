package com.matheusmelo.kybr.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SellerTextTest {

    @Test
    void aLinkInSellerTextLosesItsSchemeSeparatorAndItsClickableHost() {
        String clean = SellerText.responsavel("João http://evil.example/x");

        assertThat(clean).isEqualTo("João http[:]//evil[.]example/x");
        assertThat(clean).doesNotContain("://").doesNotContain("evil.example");
    }

    @Test
    void aBareHostAndAnAddressDomainAreNeutralisedToo() {
        assertThat(SellerText.responsavel("veja www.evil.example.com ou joao@evil.example"))
                .isEqualTo("veja www[.]evil[.]example[.]com ou joao@evil[.]example");
    }

    @Test
    void aHostEndingInAPunycodeTopLevelDomainIsNeutralised() {
        assertThat(SellerText.responsavel("veja loja.xn--p1ai e LOJA.XN--90AIS"))
                .isEqualTo("veja loja[.]xn--p1ai e LOJA[.]XN--90AIS");
    }

    @Test
    void fullWidthAndIdeographicDotsInAHostAreNeutralised() {
        assertThat(SellerText.responsavel("evil\uFF0Eexample e evil\u3002example"))
                .isEqualTo("evil[.]example e evil[.]example");
    }

    @Test
    void initialsAndOrdinaryPunctuationAreLeftAlone() {
        assertThat(SellerText.responsavel("J.R. Silva, sócio. Fim.")).isEqualTo("J.R. Silva, sócio. Fim.");
    }

    @Test
    void responsavelIsBoundedTo120Characters() {
        assertThat(SellerText.responsavel("a".repeat(200))).hasSize(120);
    }

    @Test
    void notaVendedorIsBoundedTo280Characters() {
        assertThat(SellerText.notaVendedor("b".repeat(400))).hasSize(280);
    }

    @Test
    void theBoundCountsCharactersAndNeverSplitsASurrogatePair() {
        String clean = SellerText.responsavel("😀".repeat(130));

        assertThat(clean.codePointCount(0, clean.length())).isEqualTo(120);
        assertThat(Character.isHighSurrogate(clean.charAt(clean.length() - 1))).isFalse();
    }

    @Test
    void zeroWidthJoinerAndBidiOverrideAreRemoved() {
        assertThat(SellerText.responsavel("Jo\u200Dão \u202Eanalis\u2066")).isEqualTo("João analis");
    }

    @Test
    void controlCharactersAreRemovedAndLineBreaksBecomeOneSpace() {
        assertThat(SellerText.responsavel("Maria\u0000\u0007 \r\n\tSouza\u2028Lima\u0085"))
                .isEqualTo("Maria Souza Lima");
    }

    @Test
    void textIsNfcNormalised() {
        String decomposed = Normalizer.normalize("João", Normalizer.Form.NFD);

        assertThat(SellerText.responsavel(decomposed)).isEqualTo("João");
    }

    @ParameterizedTest
    @ValueSource(strings = {"João http://evil.example/x", "a.b.c.example.com.br.", "x://y://z", "  Ana  \u200B  ",
            "evil\uFF0Eexample", "[.]evil.example[:]//", "😀😀 http://a.example"})
    void isIdempotent(String input) {
        String once = SellerText.responsavel(input);

        assertThat(SellerText.responsavel(once)).isEqualTo(once);
    }

    @Test
    void idempotentAtTheBoundWhenTheCutFallsInsideANeutralisedHost() {
        String input = "x".repeat(110) + " evil.example.com";
        String once = SellerText.responsavel(input);

        assertThat(once.codePointCount(0, once.length())).isLessThanOrEqualTo(120);
        assertThat(SellerText.responsavel(once)).isEqualTo(once);
    }

    @Test
    void razaoSocialMasksACpfEvenOneSplitByAnInvisibleCharacter() {
        assertThat(SellerText.razaoSocial("MARIA SOUZA 123.456\u200D.789-09 ME"))
                .isEqualTo("MARIA SOUZA ***.***.***-** ME");
    }

    @Test
    void razaoSocialIsBoundedTo300CharactersWithoutLeavingPartOfACpf() {
        String clean = SellerText.razaoSocial("x".repeat(295) + " 123.456.789-09");

        assertThat(clean).hasSize(300).doesNotContainPattern("[0-9]");
    }

    @Test
    void nullStaysNull() {
        assertThat(SellerText.razaoSocial(null)).isNull();
        assertThat(SellerText.responsavel(null)).isNull();
        assertThat(SellerText.notaVendedor(null)).isNull();
    }
}
