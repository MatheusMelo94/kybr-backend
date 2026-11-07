package com.matheusmelo.kybr.service.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.workspace.ContatoVendedor;
import com.matheusmelo.kybr.model.workspace.ContatoVendedor.Tipo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SellerContactRuleTest {

    @ParameterizedTest
    @ValueSource(strings = {"contato@example.com", "a.b+c_d-e@sub.empresa-exemplo.test", "x@a.co"})
    void plainAddressesArePublishable(String address) {
        assertThat(SellerContactRule.violation(new ContatoVendedor(Tipo.email, address))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a?b@x.test", "a b@x.test", "a%40@x.test", "a#b@x.test", "a&b@x.test", "ab@x",
        "@x.test", "a@-x.test", "a@x-.test", "a@x..test", "javascript:alert(1)", "a@x.test\n", ""})
    void addressesThatCannotSafelyBecomeAMailtoAreRefused(String address) {
        assertThat(SellerContactRule.violation(new ContatoVendedor(Tipo.email, address))).contains("email_format");
    }

    @Test
    void anAddressOver254CharactersIsRefused() {
        String address = "a".repeat(64) + "@" + "b".repeat(182) + ".example";

        assertThat(address).hasSizeGreaterThan(254);
        assertThat(SellerContactRule.violation(new ContatoVendedor(Tipo.email, address))).contains("email_length");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://loja.example.com/contato", "https://loja.example.com/", "https://x.test:8443/a?b=c"})
    void absoluteHttpsUrlsArePublishable(String url) {
        assertThat(SellerContactRule.violation(new ContatoVendedor(Tipo.url, url))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "http://x.test", "https://u:p@x.test", "https://u@x.test/",
        "//x.test/a", "/contato", "https:///a", "https://x.test/a b", "HTTPS://x.test/", "ftp://x.test/",
        "https://x.test/é", "https://x.test\\@y.test/", ""})
    void anythingButAnAbsoluteHttpsUrlWithoutUserinfoIsRefused(String url) {
        assertThat(SellerContactRule.violation(new ContatoVendedor(Tipo.url, url))).contains("url_format");
    }

    @Test
    void aMissingTypeOrValueIsRefused() {
        assertThat(SellerContactRule.violation(new ContatoVendedor(null, "contato@example.com"))).contains("missing");
        assertThat(SellerContactRule.violation(new ContatoVendedor(Tipo.email, null))).contains("missing");
        assertThat(SellerContactRule.violation(null)).contains("missing");
    }
}
