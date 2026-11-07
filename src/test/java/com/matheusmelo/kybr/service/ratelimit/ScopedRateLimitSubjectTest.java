package com.matheusmelo.kybr.service.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.ClientIp.Source;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject.CaseId;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject.IpUnknown;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject.SlugCnpj;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Subjects counted within a scope: an IP per link, a CNPJ per link, and a case. */
class ScopedRateLimitSubjectTest {

    @ParameterizedTest
    @EnumSource(value = Source.class, names = {"FORWARDED", "DIRECT"})
    void aPerClientIpInAScopeIsAnIpSubjectOfItsOwnPerScope(Source source) {
        ClientIp ip = new ClientIp("198.51.100.4", source);

        RateLimitSubject inA = RateLimitSubject.forClientInScope(ip, "slug-a");
        RateLimitSubject inB = RateLimitSubject.forClientInScope(ip, "slug-b");

        assertThat(inA.kind()).isEqualTo(SubjectKind.IP);
        assertThat(inA).isNotEqualTo(inB).isNotEqualTo(RateLimitSubject.forClient(ip, "slug-a"));
        assertThat(inA.value()).isNotEqualTo(inB.value());
        assertThat(inA).isEqualTo(RateLimitSubject.forClientInScope(ip, "slug-a")).hasToString("Ip[redacted]");
    }

    @ParameterizedTest
    @EnumSource(value = Source.class, names = {"FORWARDED_ABSENT", "PLATFORM_ABSENT"})
    void anUnknownOrSharedIpIsNeverAPerIpSubject(Source source) {
        RateLimitSubject subject = RateLimitSubject.forClientInScope(new ClientIp("203.0.113.10", source), "slug-a");

        assertThat(subject).isEqualTo(new IpUnknown("slug-a"));
        assertThat(subject.kind()).isEqualTo(SubjectKind.IP_UNKNOWN);
        assertThat(subject.value()).doesNotContain("203.0.113.10");
    }

    @Test
    void aCnpjPerLinkIsCanonicalisedFirst() {
        SlugCnpj formatted = new SlugCnpj("slug-a", "11.222.333/0001-81");

        assertThat(formatted).isEqualTo(new SlugCnpj("slug-a", "11222333000181"));
        assertThat(formatted.cnpj()).isEqualTo("11222333000181");
        assertThat(formatted.kind()).isEqualTo(SubjectKind.CNPJ);
        assertThat(formatted.value()).isNotEqualTo(new SlugCnpj("slug-b", "11222333000181").value());
        assertThat(formatted.toString()).doesNotContain("11222333000181");
    }

    @Test
    void anInvalidCnpjOrABlankSlugIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SlugCnpj("slug-a", "11222333000180"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SlugCnpj(" ", "11222333000181"));
    }

    @Test
    void aCaseIsItsOwnKind() {
        UUID id = UUID.randomUUID();

        CaseId subject = new CaseId(id);

        assertThat(subject.kind()).isEqualTo(SubjectKind.CASE);
        assertThat(subject.value()).isEqualTo(id.toString());
        assertThat(SubjectKind.CASE.prefix()).isEqualTo("case");
    }
}
