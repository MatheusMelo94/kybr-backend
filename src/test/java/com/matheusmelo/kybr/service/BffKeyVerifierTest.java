package com.matheusmelo.kybr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties.Bff;
import com.matheusmelo.kybr.model.BffKeySlot;
import com.matheusmelo.kybr.support.TestSecrets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BffKeyVerifierTest {

    private static final String CURRENT = TestSecrets.BFF_KEY_CURRENT;
    private static final String PREVIOUS = TestSecrets.BFF_KEY_PREVIOUS;

    private final BffKeyVerifier withBoth = new BffKeyVerifier(new Bff(CURRENT, PREVIOUS, List.of()));
    private final BffKeyVerifier currentOnly = new BffKeyVerifier(new Bff(CURRENT, null, List.of()));

    @Test
    void recognisesEachSlot() {
        assertThat(withBoth.verify(CURRENT)).isEqualTo(BffKeySlot.CURRENT);
        assertThat(withBoth.verify(PREVIOUS)).isEqualTo(BffKeySlot.PREVIOUS);
        assertThat(currentOnly.verify(CURRENT)).isEqualTo(BffKeySlot.CURRENT);
        assertThat(currentOnly.verify(PREVIOUS)).isEqualTo(BffKeySlot.NONE);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t", "not-the-key"})
    void anAbsentBlankOrWrongValueIsNone(String presented) {
        assertThat(withBoth.verify(presented)).isEqualTo(BffKeySlot.NONE);
    }

    @Test
    void nearMissesAreNone() {
        assertThat(withBoth.verify(CURRENT + " ")).isEqualTo(BffKeySlot.NONE);
        assertThat(withBoth.verify(" " + CURRENT)).isEqualTo(BffKeySlot.NONE);
        assertThat(withBoth.verify(flipCase(CURRENT))).isEqualTo(BffKeySlot.NONE);
        assertThat(withBoth.verify(CURRENT.substring(1))).isEqualTo(BffKeySlot.NONE);
    }

    @Test
    void comparesAgainstBothSlotsOnEveryCallInConstantTime() {
        List<int[]> lengths = new ArrayList<>();
        BiPredicate<byte[], byte[]> spy = (a, b) -> {
            lengths.add(new int[] {a.length, b.length});
            return MessageDigest.isEqual(a, b);
        };

        for (BffKeyVerifier verifier : List.of(
                new BffKeyVerifier(new Bff(CURRENT, PREVIOUS, List.of()), spy),
                new BffKeyVerifier(new Bff(CURRENT, null, List.of()), spy))) {
            for (String presented : new String[] {CURRENT, PREVIOUS, "wrong"}) {
                lengths.clear();
                verifier.verify(presented);
                assertThat(lengths).as(presented).hasSize(2)
                        .allSatisfy(pair -> assertThat(pair).containsExactly(32, 32));
            }
        }
    }

    private static String flipCase(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetter(c)) {
                char flipped = Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c);
                return value.substring(0, i) + flipped + value.substring(i + 1);
            }
        }
        throw new IllegalArgumentException("no letter to flip");
    }
}
