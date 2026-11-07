package com.matheusmelo.kybr.service.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.link.TestCases;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CaseTokensTest {

    private final CaseTokens tokens = new CaseTokens();

    @Test
    void tokensAre43Base64UrlCharactersAndDistinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String token = tokens.newToken();
            assertThat(token).matches("^[A-Za-z0-9_-]{22,64}$").hasSize(43);
            seen.add(token);
        }

        assertThat(seen).hasSize(10_000);
    }

    @Test
    void theHashIsLowerCaseHexSha256AsStored() {
        String token = tokens.newToken();

        assertThat(CaseTokens.hash(token)).matches("^[0-9a-f]{64}$").isEqualTo(TestCases.hash(token));
        assertThat(CaseTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @ParameterizedTest
    @ValueSource(strings = {"aaaaaaaaaaaaaaaaaaaaaa", "A-_0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXY"})
    void wellFormedTokensMatchThePattern(String token) {
        assertThat(token.length()).isBetween(22, 64);
        assertThat(CaseTokens.isWellFormed(token)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"aaaaaaaaaaaaaaaaaaaaa", "a+aaaaaaaaaaaaaaaaaaaaaa", "a/aaaaaaaaaaaaaaaaaaaaaa",
        "a=aaaaaaaaaaaaaaaaaaaaaa", "a%2Faaaaaaaaaaaaaaaaaaaa", "..aaaaaaaaaaaaaaaaaaaaaa", ""})
    void offPatternTokensAreRefused(String token) {
        assertThat(CaseTokens.isWellFormed(token)).isFalse();
        assertThat(CaseTokens.isWellFormed("a".repeat(65))).isFalse();
        assertThat(CaseTokens.isWellFormed(null)).isFalse();
    }
}
