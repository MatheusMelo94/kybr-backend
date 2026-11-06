package com.matheusmelo.kybr.service.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.config.KybrProperties.Hashing;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import com.matheusmelo.kybr.exception.RateLimitExceededException;
import com.matheusmelo.kybr.exception.ServiceUnavailableException;
import com.matheusmelo.kybr.model.RateLimitCounter;
import com.matheusmelo.kybr.repository.RateLimitCounterRepository;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject.Ip;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject.IpUnknown;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject.Slug;
import com.matheusmelo.kybr.support.TestSecrets;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** The fixed-window limiter with a fixed clock and an in-memory counter store. */
class RateLimiterTest {

    /** Window start for a 60 s window: divisible by 60. */
    private static final long WINDOW_START = 1_790_000_040L;
    private static final RateLimitPolicy THREE_PER_MINUTE =
            new RateLimitPolicy("test-ip", 3, Duration.ofSeconds(60), ErrorCode.LIMITE_TENTATIVAS, SubjectKind.IP);
    private static final RateLimitPolicy PER_SLUG =
            new RateLimitPolicy("test-slug", 3, Duration.ofSeconds(60), ErrorCode.LIMITE_CONSULTAS, SubjectKind.SLUG);
    private static final RateLimitPolicy IP_UNKNOWN = new RateLimitPolicy("test-ip-unknown", 2, Duration.ofSeconds(60),
            ErrorCode.LIMITE_TENTATIVAS, SubjectKind.IP_UNKNOWN);

    private final Map<String, Long> store = new HashMap<>();
    private final List<String> idsSeen = new ArrayList<>();
    private final MutableClock clock = new MutableClock(Instant.ofEpochSecond(WINDOW_START).plusSeconds(15));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final KeyHasher keyHasher = new KeyHasher(new Hashing(TestSecrets.HASH_SECRET, null));
    private RateLimitCounterRepository repository;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        repository = mock(RateLimitCounterRepository.class);
        when(repository.incrementAndGet(anyString(), any(Instant.class))).thenAnswer(call -> {
            String id = call.getArgument(0);
            idsSeen.add(id);
            return store.merge(id, 1L, Long::sum);
        });
        when(repository.findById(anyString())).thenAnswer(call -> {
            String id = call.getArgument(0);
            return Optional.ofNullable(store.get(id)).map(count -> new RateLimitCounter(id, count, Instant.EPOCH));
        });
        limiter = new RateLimiter(repository, keyHasher, clock, meters);
    }

    @Test
    void allowsUpToTheLimitThenDeniesWithTheSecondsToTheWindowEnd() {
        Ip ip = new Ip("198.51.100.4");

        assertThat(limiter.consume(THREE_PER_MINUTE, ip)).isEqualTo(new RateLimitDecision(true, 2, 0));
        assertThat(limiter.consume(THREE_PER_MINUTE, ip)).isEqualTo(new RateLimitDecision(true, 1, 0));
        assertThat(limiter.consume(THREE_PER_MINUTE, ip)).isEqualTo(new RateLimitDecision(true, 0, 0));
        assertThat(limiter.consume(THREE_PER_MINUTE, ip)).isEqualTo(new RateLimitDecision(false, 0, 45));
    }

    @Test
    void theNextWindowAllowsAgain() {
        Ip ip = new Ip("198.51.100.4");
        for (int i = 0; i < 4; i++) {
            limiter.consume(THREE_PER_MINUTE, ip);
        }

        clock.set(Instant.ofEpochSecond(WINDOW_START + 60));

        assertThat(limiter.consume(THREE_PER_MINUTE, ip).allowed()).isTrue();
    }

    @Test
    void retryAfterIsAtLeastOneAndRoundsUp() {
        Ip ip = new Ip("198.51.100.4");
        clock.set(Instant.ofEpochSecond(WINDOW_START + 60).minusMillis(200));
        for (int i = 0; i < 3; i++) {
            limiter.consume(THREE_PER_MINUTE, ip);
        }
        assertThat(limiter.consume(THREE_PER_MINUTE, ip).retryAfterSeconds()).isEqualTo(1);

        Ip other = new Ip("198.51.100.5");
        clock.set(Instant.ofEpochSecond(WINDOW_START).plusMillis(500));
        for (int i = 0; i < 3; i++) {
            limiter.consume(THREE_PER_MINUTE, other);
        }
        assertThat(limiter.consume(THREE_PER_MINUTE, other).retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void peekDoesNotIncrement() {
        Ip ip = new Ip("198.51.100.4");
        limiter.consume(THREE_PER_MINUTE, ip);

        assertThat(limiter.peek(THREE_PER_MINUTE, ip)).isEqualTo(new RateLimitDecision(true, 2, 0));
        assertThat(limiter.peek(THREE_PER_MINUTE, ip)).isEqualTo(new RateLimitDecision(true, 2, 0));

        limiter.consume(THREE_PER_MINUTE, ip);
        limiter.consume(THREE_PER_MINUTE, ip);
        assertThat(limiter.peek(THREE_PER_MINUTE, ip)).isEqualTo(new RateLimitDecision(false, 0, 45));
        assertThat(store).containsValue(3L);
    }

    @Test
    void peekOfAnUnusedSubjectIsTheFullLimit() {
        assertThat(limiter.peek(THREE_PER_MINUTE, new Ip("198.51.100.9"))).isEqualTo(new RateLimitDecision(true, 3, 0));
    }

    @Test
    void consumeOrThrowRendersA429WithThePolicysCodeAndDigitsOnlyRetryAfter() throws Exception {
        Ip ip = new Ip("198.51.100.4");
        for (int i = 0; i < 3; i++) {
            limiter.consumeOrThrow(THREE_PER_MINUTE, ip);
        }

        assertThatThrownBy(() -> limiter.consumeOrThrow(THREE_PER_MINUTE, ip))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.LIMITE_TENTATIVAS);
                    assertThat(e.headers()).containsEntry("Retry-After", "45");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    try {
                        new ErrorResponseWriter(JsonMapper.builder().build()).write(response, e);
                    } catch (java.io.IOException io) {
                        throw new AssertionError(io);
                    }
                    assertThat(response.getStatus()).isEqualTo(429);
                    assertThat(response.getHeader("Retry-After")).matches("^\\d+$");
                });
    }

    @Test
    void aMongoFailureFailsClosedAsServiceUnavailable() {
        when(repository.incrementAndGet(anyString(), any(Instant.class)))
                .thenThrow(new DataAccessResourceFailureException("timeout"));
        when(repository.findById(anyString())).thenThrow(new DataAccessResourceFailureException("timeout"));

        assertThatThrownBy(() -> limiter.consume(THREE_PER_MINUTE, new Ip("198.51.100.4")))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> limiter.peek(THREE_PER_MINUTE, new Ip("198.51.100.4")))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void countsDenialsPerPolicy() {
        Ip ip = new Ip("198.51.100.4");
        for (int i = 0; i < 5; i++) {
            limiter.consume(THREE_PER_MINUTE, ip);
        }

        assertThat(meters.get("kybr.ratelimit.exceeded").tag("policy", "test-ip").counter().count()).isEqualTo(2);
    }

    @Test
    void theSubjectIsStoredOnlyAsAKeyedHash() {
        limiter.consume(THREE_PER_MINUTE, new Ip("198.51.100.4"));

        assertThat(idsSeen).containsExactly(
                RateLimitCounter.idFor("test-ip", keyHasher.hmac("ip:198.51.100.4"), WINDOW_START));
        assertThat(idsSeen.getFirst()).doesNotContain("198.51.100.4");
    }

    @Test
    void theCounterExpiresOneMinuteAfterTheWindowEnd() {
        List<Instant> expiries = new ArrayList<>();
        when(repository.incrementAndGet(anyString(), any(Instant.class))).thenAnswer(call -> {
            expiries.add(call.getArgument(1));
            return 1L;
        });

        limiter.consume(THREE_PER_MINUTE, new Ip("198.51.100.4"));

        assertThat(expiries).containsExactly(Instant.ofEpochSecond(WINDOW_START + 60 + 60));
    }

    @Test
    void anIpUnknownSubjectOnAPerIpPolicyThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter.consume(THREE_PER_MINUTE, new IpUnknown("slug-a")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter.peek(THREE_PER_MINUTE, new IpUnknown("slug-a")));
        assertThatIllegalArgumentException().isThrownBy(() -> limiter.consume(PER_SLUG, new Ip("198.51.100.4")));
    }

    @Test
    void anIpUnknownPolicyCountsPerScopeAndIsMetered() {
        assertThat(limiter.consume(IP_UNKNOWN, new IpUnknown("slug-a")).allowed()).isTrue();
        assertThat(limiter.consume(IP_UNKNOWN, new IpUnknown("slug-a")).allowed()).isTrue();
        assertThat(limiter.consume(IP_UNKNOWN, new IpUnknown("slug-a")).allowed()).isFalse();
        assertThat(limiter.consume(IP_UNKNOWN, new IpUnknown("slug-b")).allowed()).isTrue();

        assertThat(meters.get("kybr.ratelimit.ip_unknown").tag("policy", "test-ip-unknown").counter().count())
                .isEqualTo(4);
    }

    @Test
    void subjectsOfDifferentKindsWithTheSameValueNeverShareACounter() {
        limiter.consume(PER_SLUG, new Slug("x"));

        assertThat(idsSeen.getFirst()).contains(keyHasher.hmac("slug:x"));
    }

    /** A clock the test can move. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
