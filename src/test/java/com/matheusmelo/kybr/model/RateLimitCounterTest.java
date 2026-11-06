package com.matheusmelo.kybr.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateLimitCounterTest {

    @Test
    void theIdIsPolicyHmacAndWindowStart() {
        assertThat(RateLimitCounter.idFor("verify-ip", "aBc-_123", 1_790_000_040L))
                .isEqualTo("verify-ip:aBc-_123:1790000040");
    }

    @ParameterizedTest
    @CsvSource({"'a:b', hmac", "policy, 'h:mac'", "'', hmac", "policy, ''"})
    void refusesPartsThatWouldMakeTheIdAmbiguous(String policy, String hmac) {
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimitCounter.idFor(policy, hmac, 1L));
    }
}
