package com.matheusmelo.kybr.acceptance;

import static com.matheusmelo.kybr.acceptance.Envelope.assertEnvelope;
import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.idempotency.IdempotencyProbeController;
import com.matheusmelo.kybr.support.qa.QaIdempotencyProbeController;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Idempotency end to end and the replay / Retry-After semantics of the contract over HTTP. The
 * single-request
 * replay, reordered keys, slug binding, replay-before-validation, header-name case and
 * content-type spellings are in {@code IdempotencyInterceptorIT} (not duplicated).
 *
 * <p>A client that gets 503 + {@code Retry-After: 1} retries with the same key, as the BFF does.
 */
@ExtendWith(OutputCaptureExtension.class)
class IdempotencyAcceptanceIT extends AcceptanceTest {

    private static final String KEY = "Idempotency-Key";
    private static final String BODY = "{\"name\":\"Loja Exemplo\",\"note\":\"synthetic\"}";

    @Autowired
    private MongoTemplate mongo;

    private String slug;
    private String key;

    @BeforeEach
    void fresh() {
        slug = "qa-" + UUID.randomUUID();
        key = UUID.randomUUID().toString();
    }

    @AfterEach
    void reset() {
        IdempotencyProbeController.reset();
    }

    /** The client loses the response; its retry replays the first result. */
    @Test
    void aRetryAfterALostResponseReplaysTheFirstResult() throws Exception {
        IdempotencyProbeController.sleep(slug, 300);
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST " + path(slug) + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                    + KEY + ": " + key + "\r\n" + BFF_KEY_HEADER + ": " + bffKey() + "\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } // closed before the response is read
        awaitCalls(slug, 1);
        IdempotencyProbeController.reset();

        Response retry = retryUntilSettled(() -> verify(slug, key, BODY));

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.jsonPath().getInt("call")).isEqualTo(1);
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
    }

    /** A mismatch leaves the stored record intact; the original request still replays. */
    @Test
    void aMismatchLeavesTheRecordIntact() {
        Response first = verify(slug, key, BODY);
        Response mismatch = verify(slug, key, "{\"name\":\"Outra Loja\",\"note\":\"synthetic\"}");

        Response again = verify(slug, key, BODY);

        assertEnvelope(mismatch, 422, "VALOR_INVALIDO");
        assertThat(mismatch.jsonPath().getString("field")).isEqualTo(KEY);
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(again.asByteArray()).isEqualTo(first.asByteArray());
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
    }

    /** Malformed keys (including the nil and max UUIDs) are 422 on the key and never execute. */
    @ParameterizedTest
    @ValueSource(strings = {"{3f2504e0-4f89-41d3-9a0c-0305e82c3301}", "3f2504e0-4f89-41d3-9a0c-0305e82c33011",
        "ffffffff-ffff-ffff-ffff-ffffffffffff", "3f2504e04f8941d39a0c0305e82c3301"})
    void aMalformedKeyIsRejectedOnTheKeyField(String badKey) {
        Response response = request().header(KEY, badKey).body(BODY).post(path(slug));

        assertEnvelope(response, 422, "VALOR_INVALIDO");
        assertThat(response.jsonPath().getString("field")).isEqualTo(KEY);
        assertThat(IdempotencyProbeController.calls(slug)).isZero();
    }

    /** Any UUID version is accepted (v1, v7). */
    @ParameterizedTest
    @ValueSource(strings = {"c232ab00-9414-11ec-b3c8-9f6bdeced846", "01890a5d-ac96-774b-bcce-b302099a8057"})
    void anyUuidVersionIsAccepted(String uuid) {
        assertThat(verify(slug, uuid, BODY).statusCode()).isEqualTo(201);
    }

    /** The key value is normalised; an uppercase retry replays. */
    @Test
    void anUppercaseRetryOfTheSameKeyReplays() {
        Response first = verify(slug, key, BODY);

        Response upper = verify(slug, key.toUpperCase(), BODY);

        assertThat(upper.statusCode()).isEqualTo(201);
        assertThat(upper.asByteArray()).isEqualTo(first.asByteArray());
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
    }

    /** 10 identical requests in parallel execute once; the rest replay or get 503 + Retry-After: 1. */
    @Test
    void tenParallelIdenticalRequestsExecuteOnce() throws Exception {
        IdempotencyProbeController.sleep(slug, 500);

        List<Response> responses = parallel(10, i -> verify(slug, key, BODY));

        byte[] winner = null;
        for (Response r : responses) {
            if (r.statusCode() == 201) {
                if (winner == null) {
                    winner = r.asByteArray();
                }
                assertThat(r.asByteArray()).isEqualTo(winner);
            } else {
                assertEnvelope(r, 503, "INDISPONIVEL");
                assertThat(r.header("Retry-After")).isEqualTo("1");
            }
        }
        assertThat(winner).as("at least one 201").isNotNull();
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
        IdempotencyProbeController.reset();
        Response settled = verify(slug, key, BODY);
        assertThat(settled.asByteArray()).as("after Retry-After the retry replays").isEqualTo(winner);
    }

    /** The same key with different bodies in parallel never executes twice. */
    @Test
    void tenParallelDifferentBodiesWithOneKeyExecuteOnce() throws Exception {
        IdempotencyProbeController.sleep(slug, 300);

        List<Response> responses = parallel(10,
                i -> verify(slug, key, "{\"name\":\"Loja " + i + "\",\"note\":\"synthetic\"}"));

        assertThat(responses).extracting(Response::statusCode).allMatch(s -> s == 201 || s == 422 || s == 503);
        assertThat(responses).extracting(Response::statusCode).filteredOn(s -> s == 201).hasSize(1);
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
    }

    /** Non-storable outcomes (5xx, 429) are not stored; a retry re-executes. */
    @ParameterizedTest
    @ValueSource(ints = {500, 503, 429})
    void aNonStorableOutcomeIsRetriedFresh(int status) {
        String scenario = "qa-ns-" + UUID.randomUUID();
        QaIdempotencyProbeController.status(scenario, status);
        Response first = panel(scenario, key);
        QaIdempotencyProbeController.clearStatus(scenario);

        Response retry = panel(scenario, key);

        assertThat(first.statusCode()).isEqualTo(status);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(QaIdempotencyProbeController.calls(scenario)).isEqualTo(2);
    }

    /** A storable 4xx is replayed, not re-executed. */
    @ParameterizedTest
    @ValueSource(ints = {404, 409})
    void aStorable4xxIsReplayed(int status) {
        String scenario = "qa-s-" + UUID.randomUUID();
        QaIdempotencyProbeController.status(scenario, status);
        Response first = panel(scenario, key);
        QaIdempotencyProbeController.clearStatus(scenario);

        Response retry = panel(scenario, key);

        assertThat(retry.statusCode()).isEqualTo(status);
        assertThat(retry.asByteArray()).isEqualTo(first.asByteArray());
        assertThat(QaIdempotencyProbeController.calls(scenario)).isEqualTo(1);
    }

    /** The route is part of the request hash; one key per intent. */
    @Test
    void theSameKeyOnAnotherIdempotentRouteIsAMismatch() {
        String scenario = "qa-r-" + UUID.randomUUID();
        assertThat(panel(scenario, key).statusCode()).isEqualTo(201);

        Response other = http().contentType("application/json").header(KEY, key).body("{\"a\":1}")
                .post("/test/qa/idem-other/" + scenario);

        assertEnvelope(other, 422, "VALOR_INVALIDO");
        assertThat(QaIdempotencyProbeController.calls(scenario)).isEqualTo(1);
    }

    /** No raw key in the store, the replay body is ciphertext, and neither reaches the logs. */
    @Test
    void theStoreHoldsNoRawKeyNorPlaintextBodyAndTheLogsNeither(CapturedOutput output) {
        String scenario = "qa-store-" + UUID.randomUUID();
        Response first = panel(scenario, key);
        assertThat(panel(scenario, key).asByteArray()).isEqualTo(first.asByteArray());

        List<Document> all = mongo.getCollection("idempotency_records").find().into(new ArrayList<>());
        String canary = QaIdempotencyProbeController.BODY_CANARY_PREFIX + scenario;

        assertThat(all).isNotEmpty();
        assertThat(all).allSatisfy(doc -> {
            assertThat(doc.get("_id").toString()).isNotEqualTo(key);
            assertThat(doc.toJson()).doesNotContain(key, key.replace("-", ""), canary, scenario);
        });
        assertThat(output.getAll()).doesNotContain(key, canary);
        assertThat(mongo.getCollection("idempotency_records").listIndexes().into(new ArrayList<>()))
                .anySatisfy(index -> {
                    assertThat(index.get("key", Document.class)).containsKey("expiresAt");
                    assertThat(((Number) index.get("expireAfterSeconds")).longValue()).isZero();
                });
    }

    private interface Call {
        Response call(int i) throws Exception;
    }

    private static List<Response> parallel(int n, Call call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call(index);
                }));
            }
            start.countDown();
        }
        List<Response> responses = new ArrayList<>();
        for (Future<Response> f : futures) {
            responses.add(f.get());
        }
        return responses;
    }

    /** Retries on 503 + Retry-After (honouring the delay), as the BFF does, until the outcome settles. */
    private static Response retryUntilSettled(java.util.concurrent.Callable<Response> call) throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            Response response = call.call();
            if (response.statusCode() != 503) {
                return response;
            }
            Thread.sleep(Long.parseLong(response.header("Retry-After")) * 1000);
        }
        throw new AssertionError("still in progress after 10 retries");
    }

    private Response panel(String scenario, String idempotencyKey) {
        return http().contentType("application/json").header(KEY, idempotencyKey).body("{\"a\":1}")
                .post("/test/qa/idem/" + scenario);
    }

    private Response verify(String targetSlug, String idempotencyKey, String body) {
        return request().header(KEY, idempotencyKey).body(body).post(path(targetSlug));
    }

    private RequestSpecification request() {
        return http().contentType("application/json");
    }

    private static String path(String targetSlug) {
        return "/public/v/" + targetSlug + "/verify-test";
    }

    private static void awaitCalls(String targetSlug, int calls) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (IdempotencyProbeController.calls(targetSlug) < calls && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(IdempotencyProbeController.calls(targetSlug)).isEqualTo(calls);
    }
}
