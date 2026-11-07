package com.matheusmelo.kybr.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GovBrUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://www.gov.br/receitafederal/pt-br", "https://gov.br/x", "HTTPS://servicos.receita.fazenda.gov.br/"})
    void acceptsHttpsOnGovBrAndItsSubdomains(String url) {
        assertThat(GovBrUrl.isGovBr(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://www.gov.br/x", "https://evilgov.br/x", "https://gov.br.example.test/x",
        "https://user@www.gov.br/x", "javascript:alert(1)", "https://www.gov.br:443@example.test/", "", "not a url"})
    void refusesAnythingElse(String url) {
        assertThat(GovBrUrl.isGovBr(url)).isFalse();
        assertThat(GovBrUrl.isGovBr(null)).isFalse();
    }
}
