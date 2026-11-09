package com.matheusmelo.kybr.service.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.ClientIp.Source;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject.Ip;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject.IpUnknown;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The only way from a ClientIp to a rate-limit subject. */
class RateLimitSubjectTest {

    @ParameterizedTest
    @EnumSource(value = Source.class, names = {"FORWARDED", "DIRECT"})
    void aPerClientIdentityBecomesAnIpSubject(Source source) {
        RateLimitSubject subject = RateLimitSubject.forClient(new ClientIp("198.51.100.4", source), "slug-a");

        assertThat(subject).isEqualTo(new Ip("198.51.100.4"));
        assertThat(subject.kind()).isEqualTo(SubjectKind.IP);
    }

    @ParameterizedTest
    @EnumSource(value = Source.class, names = {"FORWARDED_ABSENT", "PLATFORM_ABSENT"})
    void aSharedAddressBecomesIpUnknownForTheScope(Source source) {
        RateLimitSubject subject = RateLimitSubject.forClient(new ClientIp("203.0.113.10", source), "slug-a");

        assertThat(subject).isEqualTo(new IpUnknown("slug-a"));
        assertThat(subject.value()).doesNotContain("203.0.113.10");
    }

    @Test
    void requiresAClientIpAndAScope() {
        assertThatNullPointerException().isThrownBy(() -> RateLimitSubject.forClient(null, "slug-a"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> RateLimitSubject.forClient(new ClientIp("203.0.113.10", Source.FORWARDED_ABSENT), " "));
    }

    @Test
    void theIpSubjectNeverPrintsTheAddress() {
        assertThat(RateLimitSubject.forClient(new ClientIp("198.51.100.4", Source.DIRECT), "s"))
                .hasToString("Ip[redacted]");
    }

    @Test
    void anEmailSubjectIgnoresCaseAndSurroundingSpaceAndNeverPrintsTheAddress() {
        RateLimitSubject upper = new RateLimitSubject.Email(" Vendedor@Example.COM ");
        RateLimitSubject lower = new RateLimitSubject.Email("vendedor@example.com");

        assertThat(upper).isEqualTo(lower);
        assertThat(upper.kind()).isEqualTo(SubjectKind.EMAIL);
        assertThat(upper.value()).isEqualTo("vendedor@example.com");
        assertThat(upper.toString()).doesNotContain("vendedor");
        assertThat(SubjectKind.EMAIL.prefix()).isEqualTo("email");
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitSubject.Email(" "));
    }
}
