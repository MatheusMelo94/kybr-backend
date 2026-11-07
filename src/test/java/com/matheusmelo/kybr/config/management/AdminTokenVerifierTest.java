package com.matheusmelo.kybr.config.management;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.TokenSlot;
import com.matheusmelo.kybr.support.TestSecrets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminTokenVerifierTest {

    private static final String CURRENT = TestSecrets.ADMIN_TOKEN;
    private static final String PREVIOUS = TestSecrets.ADMIN_TOKEN_PREVIOUS;

    private final AdminTokenVerifier verifier = new AdminTokenVerifier(CURRENT, PREVIOUS);

    @Test
    void theCurrentTokenAuthenticatesAsTheCurrentSlot() {
        assertThat(verifier.verify(CURRENT)).contains(TokenSlot.CURRENT);
    }

    @Test
    void thePreviousTokenAuthenticatesAsThePreviousSlotDuringARotation() {
        assertThat(verifier.verify(PREVIOUS)).contains(TokenSlot.PREVIOUS);
    }

    @Test
    void anyOtherValueIsRefusedByteForByte() {
        assertThat(verifier.verify(null)).isEmpty();
        assertThat(verifier.verify("")).isEmpty();
        assertThat(verifier.verify(CURRENT + " ")).isEmpty();
        assertThat(verifier.verify(" " + CURRENT)).isEmpty();
        assertThat(verifier.verify(CURRENT.toUpperCase(Locale.ROOT))).isEmpty();
        assertThat(verifier.verify(TestSecrets.BFF_KEY_CURRENT)).isEmpty();
    }

    @Test
    void withoutAPreviousTokenOnlyTheCurrentOneWorks() {
        AdminTokenVerifier single = new AdminTokenVerifier(CURRENT, null);

        assertThat(single.verify(CURRENT)).contains(TokenSlot.CURRENT);
        assertThat(single.verify(PREVIOUS)).isEmpty();
    }

    @Test
    void bothSlotsAreComparedOnEveryCallWhateverMatches() {
        List<String> comparisons = new ArrayList<>();
        AdminTokenVerifier counting = new AdminTokenVerifier(CURRENT, PREVIOUS, (a, b) -> {
            comparisons.add("compare");
            return MessageDigest.isEqual(a, b);
        });

        Optional<TokenSlot> current = counting.verify(CURRENT);
        int afterCurrent = comparisons.size();
        counting.verify("wrong");

        assertThat(current).contains(TokenSlot.CURRENT);
        assertThat(afterCurrent).isEqualTo(2);
        assertThat(comparisons).hasSize(4);
    }
}
