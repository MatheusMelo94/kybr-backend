package com.matheusmelo.kybr.service.ratelimit;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.matheusmelo.kybr.exception.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimitPolicyTest {

    @Test
    void refusesANon429Code() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RateLimitPolicy("p", 3, Duration.ofSeconds(60), ErrorCode.VALOR_INVALIDO, SubjectKind.IP));
    }

    @Test
    void refusesANonPositiveLimit() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RateLimitPolicy("p", 0, Duration.ofSeconds(60), ErrorCode.LIMITE_TENTATIVAS, SubjectKind.IP));
    }

    @Test
    void refusesAWindowThatIsNotAWholePositiveNumberOfSeconds() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RateLimitPolicy("p", 3, Duration.ofMillis(1500), ErrorCode.LIMITE_TENTATIVAS, SubjectKind.IP));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RateLimitPolicy("p", 3, Duration.ZERO, ErrorCode.LIMITE_TENTATIVAS, SubjectKind.IP));
    }

    @Test
    void refusesANameThatWouldBreakTheCounterId() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RateLimitPolicy("a:b", 3, Duration.ofSeconds(60), ErrorCode.LIMITE_TENTATIVAS, SubjectKind.IP));
    }
}
