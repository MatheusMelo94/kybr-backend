package com.matheusmelo.kybr.service.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.KybrProperties.LimitWindow;
import com.matheusmelo.kybr.config.KybrProperties.UploadLimits;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The document upload's request limits, with the configured defaults, over in-memory counters. */
class UploadRateLimitsTest {

    private static final ClientIp SOCKET = new ClientIp("198.51.100.4", Source.DIRECT);
    private static final ClientIp OTHER_SOCKET = new ClientIp("198.51.100.5", Source.DIRECT);
    private static final ClientIp ABSENT = new ClientIp("203.0.113.10", Source.PLATFORM_ABSENT);

    private final Map<String, Long> counters = new HashMap<>();
    private final Workspace workspace = TestWorkspaces.builder().build();
    private final Workspace otherWorkspace = TestWorkspaces.builder().build();
    private UploadRateLimits limits;

    static UploadLimits defaults() {
        return new UploadLimits(new LimitWindow(120, Duration.ofMinutes(10)), new LimitWindow(300, Duration.ofMinutes(10)),
                new LimitWindow(60, Duration.ofDays(1)));
    }

    @BeforeEach
    void setUp() {
        RateLimitCounterRepository repository = mock(RateLimitCounterRepository.class);
        when(repository.incrementAndGet(anyString(), any(Instant.class)))
                .thenAnswer(call -> counters.merge(call.getArgument(0), 1L, Long::sum));
        Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:30Z"), ZoneOffset.UTC);
        RateLimiter limiter = new RateLimiter(repository, new KeyHasher(new KybrProperties.Hashing(
                TestSecrets.HASH_SECRET, null)), clock, new SimpleMeterRegistry());
        limits = new UploadRateLimits(limiter, defaults());
    }

    private static void assertLimited(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.LIMITE_TENTATIVAS);
            assertThat(e.headers()).containsKey("Retry-After");
        });
    }

    @Test
    void theHundredAndTwentyFirstRequestFromOneSocketIpIsRefused() {
        for (int i = 0; i < 120; i++) {
            limits.request(SOCKET, workspace.slug());
        }

        assertLimited(() -> limits.request(SOCKET, workspace.slug()));
        assertLimited(() -> limits.request(SOCKET, otherWorkspace.slug()));
        limits.request(OTHER_SOCKET, workspace.slug());
    }

    @Test
    void anAbsentPlatformAddressIsCountedPerLink() {
        for (int i = 0; i < 300; i++) {
            limits.request(ABSENT, workspace.slug());
        }

        assertLimited(() -> limits.request(ABSENT, workspace.slug()));
        limits.request(ABSENT, otherWorkspace.slug());
        limits.request(SOCKET, workspace.slug());
    }

    @Test
    void theSixtyFirstRequestForOneCaseInADayIsRefused() {
        UUID caseId = UUID.randomUUID();
        for (int i = 0; i < 60; i++) {
            limits.perCase(caseId);
        }

        assertLimited(() -> limits.perCase(caseId));
        limits.perCase(UUID.randomUUID());
    }
}
