package com.matheusmelo.kybr.acceptance;

import static com.matheusmelo.kybr.acceptance.Envelope.assertEnvelope;
import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.qa.QaClock;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Rate limiting end to end and the Retry-After semantics of the contract:
 * {@code Retry-After = max(1, ceil(windowEnd − now))}, digits only, through the real limiter, real
 * MongoDB counters and the real 429 rendering ({@code QaRateLimitProbeController}, movable test clock).
 */
class RateLimitAcceptanceIT extends AcceptanceTest {

    /** A window start: 60 s windows align to whole minutes. */
    private static final Instant WINDOW_START = Instant.parse("2030-01-01T10:00:00Z");

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private MongoTemplate mongo;

    private String policy;

    @BeforeEach
    void freshPolicy() {
        policy = "qa-rl-" + UUID.randomUUID();
        QaClock.INSTANCE.set(WINDOW_START.plusSeconds(15));
    }

    /** 3/60 s at window start + 15 s; calls 1–3 pass, the 4th is 429 with Retry-After: 45. */
    @Test
    void theFourthCallIsDeniedWithTheSecondsLeftInTheWindow() {
        for (int i = 1; i <= 3; i++) {
            assertThat(consume().statusCode()).as("call " + i).isEqualTo(200);
        }

        Response denied = consume();

        assertEnvelope(denied, 429, "LIMITE_TENTATIVAS");
        assertThat(denied.header("Retry-After")).isEqualTo("45");
    }

    /** Retry-After is never 0 and rounds up; the next window allows again. */
    @Test
    void retryAfterRoundsUpIsNeverZeroAndTheNextWindowAllows() {
        QaClock.INSTANCE.set(WINDOW_START);
        exhaust();
        QaClock.INSTANCE.set(WINDOW_START.plusMillis(500));
        assertThat(consume().header("Retry-After")).as("59.5 s left").isEqualTo("60");
        QaClock.INSTANCE.set(WINDOW_START.plusSeconds(60).minusMillis(200));
        assertThat(consume().header("Retry-After")).as("0.2 s left").isEqualTo("1");
        QaClock.INSTANCE.set(WINDOW_START.plusSeconds(60));

        assertThat(consume().statusCode()).as("a new window").isEqualTo(200);
    }

    /** 20 parallel calls with limit 3 in one window → exactly 3 allowed, 17 denied. */
    @Test
    void parallelCallsAdmitExactlyTheLimit() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return consume();
                }));
            }
            start.countDown();
        }
        int allowed = 0;
        int denied = 0;
        for (Future<Response> f : futures) {
            int status = f.get().statusCode();
            if (status == 200) {
                allowed++;
            } else if (status == 429) {
                denied++;
            }
        }
        assertThat(allowed).isEqualTo(3);
        assertThat(denied).isEqualTo(17);
    }

    /** Buckets are per client IP; two spellings of one address share a bucket. */
    @Test
    void bucketsArePerCanonicalClientIp() {
        for (int i = 0; i < 3; i++) {
            assertThat(consumeAs("2001:db8::1").statusCode()).isEqualTo(200);
        }

        assertThat(consumeAs("2001:DB8:0:0:0:0:0:1").statusCode()).as("same address, other spelling").isEqualTo(429);
        assertThat(consumeAs("2001:db8::2").statusCode()).as("another address").isEqualTo(200);
        assertThat(http().header("X-Kybr-Client-IP", "2001:db8::1").get("/test/qa/rate/" + policy + "-other").statusCode())
                .as("another policy on the same address").isEqualTo(200);
    }

    /** Counters hold no raw IP; expiresAt = windowEnd + 60 s. */
    @Test
    void countersHoldNoRawSubjectAndExpireAMinuteAfterTheWindow() {
        consumeAs("198.51.100.44");

        List<Document> docs = mongo.getCollection("rate_limit_counters")
                .find(new Document("_id", new Document("$regex", "^" + policy + ":"))).into(new ArrayList<>());

        assertThat(docs).hasSize(1);
        assertThat(docs.getFirst().toJson()).doesNotContain("198.51.100.44");
        assertThat(docs.getFirst().getDate("expiresAt").toInstant()).isEqualTo(WINDOW_START.plusSeconds(120));
    }

    /** Each denial counts once in kybr_ratelimit_exceeded_total{policy}. */
    @Test
    void eachDenialIsCountedOnce() {
        for (int i = 0; i < 5; i++) {
            consumeAs("198.51.100.45");
        }

        assertThat(Metrics.sample(managementPort, "kybr_ratelimit_exceeded_total", "policy=\"" + policy + "\""))
                .isEqualTo(2.0);
    }

    /** A keyed request without a usable client IP is never counted as a per-IP subject. */
    @Test
    void aSharedAddressFallsIntoTheIpUnknownBucket() {
        Response response = http().get("/test/qa/rate/" + policy);

        assertThat(response.jsonPath().getString("subject_kind")).isEqualTo("IP_UNKNOWN");
    }

    /**
     * Light performance smoke (informational): 10 concurrent clients, 200 keyed requests through a
     * rate-limited route. No 5xx is the gate; p95 is reported, not asserted (local stack only).
     */
    @Test
    void lightSmokeHasNo5xx() throws Exception {
        List<Future<long[]>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 200; i++) {
                String ip = "198.51.100." + (i % 50 + 100);
                futures.add(pool.submit(() -> {
                    long start = System.nanoTime();
                    int status = http().header("X-Kybr-Client-IP", ip).get("/test/qa/rate/" + policy + "?limit=1000")
                            .statusCode();
                    return new long[] {status, (System.nanoTime() - start) / 1_000_000};
                }));
            }
        }
        List<Long> millis = new ArrayList<>();
        for (Future<long[]> f : futures) {
            long[] r = f.get();
            assertThat(r[0]).as("status").isEqualTo(200);
            millis.add(r[1]);
        }
        millis.sort(Long::compare);
        System.out.println("Rate-limit smoke p50=" + millis.get(99) + "ms p95=" + millis.get(189) + "ms max="
                + millis.get(199) + "ms");
    }

    private void exhaust() {
        for (int i = 0; i < 3; i++) {
            consume();
        }
    }

    private Response consume() {
        return http().get("/test/qa/rate/" + policy);
    }

    private Response consumeAs(String ip) {
        return http().header("X-Kybr-Client-IP", ip).get("/test/qa/rate/" + policy);
    }
}
