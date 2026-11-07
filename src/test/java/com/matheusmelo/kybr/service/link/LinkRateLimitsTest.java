package com.matheusmelo.kybr.service.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.KybrProperties.LimitWindow;
import com.matheusmelo.kybr.config.KybrProperties.LinkLimits;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.RateLimitExceededException;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.ClientIp.Source;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.RateLimitCounterRepository;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.service.ratelimit.RateLimiter;
import com.matheusmelo.kybr.support.TestSecrets;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The seller link's request limits, with the plan's defaults, over in-memory counters. */
class LinkRateLimitsTest {

    private static final ClientIp IP = new ClientIp("198.51.100.4", Source.FORWARDED);
    private static final ClientIp OTHER_IP = new ClientIp("198.51.100.5", Source.FORWARDED);
    private static final ClientIp SHARED = new ClientIp("203.0.113.10", Source.FORWARDED_ABSENT);
    private static final String CNPJ = "11222333000181";
    private static final String OTHER_CNPJ = "11444777000161";

    private final Map<String, Long> counters = new HashMap<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Workspace workspace = TestWorkspaces.builder().build();
    private final Workspace otherWorkspace = TestWorkspaces.builder().build();
    private LinkRateLimits limits;

    @BeforeEach
    void setUp() {
        RateLimitCounterRepository repository = mock(RateLimitCounterRepository.class);
        when(repository.incrementAndGet(anyString(), any(Instant.class)))
                .thenAnswer(call -> counters.merge(call.getArgument(0), 1L, Long::sum));
        Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:30Z"), ZoneOffset.UTC);
        RateLimiter limiter = new RateLimiter(repository, new KeyHasher(new KybrProperties.Hashing(
                TestSecrets.HASH_SECRET, null)), clock, meters);
        limits = new LinkRateLimits(limiter, defaults(), meters);
    }

    @Test
    void aPerClientIpUsesThePerIpLookupPolicyAcrossLinks() {
        repeat(5, () -> limits.lookup(IP, workspace));

        assertDenied(() -> limits.lookup(IP, otherWorkspace), ErrorCode.LIMITE_CONSULTAS, 30);
        assertThatNoException().isThrownBy(() -> limits.lookup(OTHER_IP, workspace));
    }

    @Test
    void theDailyLookupLimitDeniesThe31stInADay() {
        LinkRateLimits daily = withLimits(l -> l.lookupIp = new LimitWindow(100, Duration.ofMinutes(1)));

        repeat(30, () -> daily.lookup(IP, workspace));

        assertDenied(() -> daily.lookup(IP, workspace), ErrorCode.LIMITE_CONSULTAS, 0);
    }

    @Test
    void aSharedIpUsesTheUnknownIpLookupPolicyPerLink() {
        repeat(120, () -> limits.lookup(SHARED, workspace));

        assertDenied(() -> limits.lookup(SHARED, workspace), ErrorCode.LIMITE_CONSULTAS, 0);
        assertThatNoException().isThrownBy(() -> limits.lookup(SHARED, otherWorkspace));
        assertThat(meters.counter("kybr.ratelimit.ip_unknown", "policy", "link.lookup.ip-unknown").count())
                .isEqualTo(122);
    }

    @Test
    void verifyPerIpCountsEachLinkSeparately() {
        for (int i = 0; i < 10; i++) {
            limits.verify(IP, workspace, distinctCnpj(i));
        }

        assertDenied(() -> limits.verify(IP, workspace, CNPJ), ErrorCode.LIMITE_TENTATIVAS, 0);
        assertThatNoException().isThrownBy(() -> limits.verify(IP, otherWorkspace, CNPJ));
    }

    @Test
    void theIpLimitIsCheckedBeforeTheCnpjLimit() {
        LinkRateLimits tight = withLimits(l -> l.verifyIp = new LimitWindow(3, Duration.ofMinutes(10)));
        repeat(3, () -> tight.verify(IP, workspace, CNPJ));

        assertDenied(() -> tight.verify(IP, workspace, CNPJ), ErrorCode.LIMITE_TENTATIVAS, 0);
    }

    @Test
    void theFourthVerificationOfACnpjOnALinkWithinAnHourIsLimiteCnpj() {
        limits.verify(IP, workspace, CNPJ);
        limits.verify(OTHER_IP, workspace, CNPJ);
        limits.verify(SHARED, workspace, "11.222.333/0001-81");

        assertDenied(() -> limits.verify(new ClientIp("198.51.100.6", Source.DIRECT), workspace, CNPJ),
                ErrorCode.LIMITE_CNPJ, 0);
        assertThatNoException().isThrownBy(() -> limits.verify(IP, otherWorkspace, CNPJ));
        assertThatNoException().isThrownBy(() -> limits.verify(IP, workspace, OTHER_CNPJ));
    }

    @Test
    void aSharedIpUsesTheUnknownIpVerifyPolicyPerLink() {
        LinkRateLimits tight = withLimits(l -> l.verifyIpUnknown = new LimitWindow(2, Duration.ofMinutes(10)));
        tight.verify(SHARED, workspace, CNPJ);
        tight.verify(SHARED, workspace, OTHER_CNPJ);

        assertDenied(() -> tight.verify(SHARED, workspace, distinctCnpj(1)), ErrorCode.LIMITE_TENTATIVAS, 0);
        assertThatNoException().isThrownBy(() -> tight.verify(SHARED, otherWorkspace, CNPJ));
    }

    @Test
    void thePerLinkVolumeCounterMovesAndNeverDenies() {
        for (int i = 0; i < 50; i++) {
            limits.verify(new ClientIp("198.51.100." + (i + 10), Source.FORWARDED), workspace, distinctCnpj(i));
        }

        assertThat(meters.counter("kybr.link.verify", "slug", workspace.slug(), "resultado", "accepted").count())
                .isEqualTo(50);
    }

    @Test
    void caseReadsAllow300PerMinutePerIp() {
        repeat(300, () -> limits.caseRead(IP, workspace));

        assertDenied(() -> limits.caseRead(IP, otherWorkspace), ErrorCode.LIMITE_TENTATIVAS, 30);
        assertThatNoException().isThrownBy(() -> limits.caseRead(SHARED, workspace));
    }

    @Test
    void contactChangesAllow20PerHourPerIp() {
        repeat(20, () -> limits.contact(IP, workspace));

        assertDenied(() -> limits.contact(IP, workspace), ErrorCode.LIMITE_TENTATIVAS, 0);
    }

    @Test
    void contactChangesAllowFivePerCasePerDay() {
        UUID caseId = UUID.randomUUID();
        repeat(5, () -> limits.contactChange(caseId));

        assertDenied(() -> limits.contactChange(caseId), ErrorCode.LIMITE_TENTATIVAS, 0);
        assertThatNoException().isThrownBy(() -> limits.contactChange(UUID.randomUUID()));
    }

    private LinkRateLimits withLimits(Consumer<MutableLimits> change) {
        MutableLimits mutable = new MutableLimits();
        change.accept(mutable);
        RateLimitCounterRepository repository = mock(RateLimitCounterRepository.class);
        Map<String, Long> fresh = new HashMap<>();
        when(repository.incrementAndGet(anyString(), any(Instant.class)))
                .thenAnswer(call -> fresh.merge(call.getArgument(0), 1L, Long::sum));
        RateLimiter limiter = new RateLimiter(repository,
                new KeyHasher(new KybrProperties.Hashing(TestSecrets.HASH_SECRET, null)),
                Clock.fixed(Instant.parse("2026-11-07T12:00:30Z"), ZoneOffset.UTC), meters);
        return new LinkRateLimits(limiter, mutable.build(), meters);
    }

    private static LinkLimits defaults() {
        return new MutableLimits().build();
    }

    /** The plan's defaults, changeable one entry at a time. */
    private static final class MutableLimits {
        LimitWindow lookupIp = new LimitWindow(5, Duration.ofMinutes(1));
        LimitWindow lookupIpDaily = new LimitWindow(30, Duration.ofDays(1));
        LimitWindow lookupIpUnknown = new LimitWindow(120, Duration.ofMinutes(1));
        LimitWindow verifyIp = new LimitWindow(10, Duration.ofMinutes(10));
        LimitWindow verifyIpUnknown = new LimitWindow(300, Duration.ofMinutes(10));
        LimitWindow verifyCnpj = new LimitWindow(3, Duration.ofHours(1));
        LimitWindow caseIp = new LimitWindow(300, Duration.ofMinutes(1));
        LimitWindow caseIpUnknown = new LimitWindow(1200, Duration.ofMinutes(1));
        LimitWindow contactIp = new LimitWindow(20, Duration.ofHours(1));
        LimitWindow contactIpUnknown = new LimitWindow(200, Duration.ofHours(1));
        LimitWindow contactCase = new LimitWindow(5, Duration.ofDays(1));

        LinkLimits build() {
            return new LinkLimits(lookupIp, lookupIpDaily, lookupIpUnknown, verifyIp, verifyIpUnknown, verifyCnpj,
                    caseIp, caseIpUnknown, contactIp, contactIpUnknown, contactCase);
        }
    }

    /** Valid synthetic CNPJs, distinct per index (check digits computed). */
    private static String distinctCnpj(int index) {
        String base = String.format("%08d0001", 10_000_000 + index);
        int d1 = checkDigit(base);
        int d2 = checkDigit(base + d1);
        return base + d1 + d2;
    }

    private static int checkDigit(String digits) {
        int sum = 0;
        int weight = 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            sum += (digits.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    private static void repeat(int times, Runnable call) {
        for (int i = 0; i < times; i++) {
            call.run();
        }
    }

    /** Denied with the code; {@code retryAfterAtLeast} > 0 checks the Retry-After lower bound. */
    private static void assertDenied(ThrowingCallable call, ErrorCode code, long retryAfterAtLeast) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(Long.parseLong(e.headers().get("Retry-After"))).isPositive()
                    .isGreaterThanOrEqualTo(retryAfterAtLeast);
        });
    }
}
