package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.matheusmelo.kybr.support.idempotency.IdempotencyProbeController;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import com.matheusmelo.kybr.support.web.RawHttp;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** At slice level: the idempotency web pipeline end to end. */
class IdempotencyInterceptorIT extends WebIntegrationTest {

    private static final String BODY = "{\"name\":\"Loja Exemplo\",\"note\":\"synthetic\"}";
    private static final String KEY_HEADER = "Idempotency-Key";

    @Autowired
    private MeterRegistry meters;

    private String slug;
    private String key;

    @BeforeEach
    void freshSlugAndKey() {
        slug = "slug-" + UUID.randomUUID();
        key = UUID.randomUUID().toString();
    }

    @AfterEach
    void resetToggles() {
        IdempotencyProbeController.reset();
    }

    @Test
    void aRepeatReplaysTheExactStatusAndBodyWithoutInvokingTheController() {
        Response first = verify(slug, key, BODY);
        Response repeat = verify(slug, key, BODY);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(repeat.statusCode()).isEqualTo(201);
        assertThat(repeat.asByteArray()).isEqualTo(first.asByteArray());
        assertThat(repeat.contentType()).isEqualTo(first.contentType());
        assertThat(repeat.header("X-Request-Id")).isNotEqualTo(first.header("X-Request-Id"));
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
    }

    @Test
    void reorderedKeysAndWhitespaceStillReplay() {
        verify(slug, key, BODY);

        Response repeat = verify(slug, key, "{ \"note\" : \"synthetic\",\n \"name\" : \"Loja Exemplo\" }");

        assertThat(repeat.statusCode()).isEqualTo(201);
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
    }

    @Test
    void theSameKeyWithAnotherBodyIsA422OnTheKey() {
        verify(slug, key, BODY);

        Response mismatch = verify(slug, key, "{\"name\":\"Outra Loja\",\"note\":\"synthetic\"}");

        assertEnvelope(mismatch, 422, "VALOR_INVALIDO");
        assertThat(mismatch.jsonPath().getString("field")).isEqualTo(KEY_HEADER);
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
    }

    @Test
    void theSameKeyOnAnotherSlugExecutesIndependently() {
        String otherSlug = slug + "-other";

        assertThat(verify(slug, key, BODY).statusCode()).isEqualTo(201);
        assertThat(verify(otherSlug, key, BODY).statusCode()).isEqualTo(201);

        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
        assertThat(IdempotencyProbeController.calls(otherSlug)).isEqualTo(1);
    }

    @Test
    void replayHappensBeforeValidation() {
        Response first = verify(slug, key, BODY);
        IdempotencyProbeController.strict(true);

        Response repeat = verify(slug, key, BODY);

        assertThat(repeat.statusCode()).isEqualTo(201);
        assertThat(repeat.asByteArray()).isEqualTo(first.asByteArray());
        assertThat(verify(slug, UUID.randomUUID().toString(), BODY).statusCode())
                .as("the tightened rule does apply to a fresh key").isEqualTo(422);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid", "00000000-0000-0000-0000-000000000000"})
    void aMalformedKeyIsA422OnTheKey(String badKey) {
        Response response = request().header(KEY_HEADER, badKey).body(BODY).post(path(slug));

        assertEnvelope(response, 422, "VALOR_INVALIDO");
        assertThat(response.jsonPath().getString("field")).isEqualTo(KEY_HEADER);
        assertThat(IdempotencyProbeController.calls(slug)).isZero();
    }

    @Test
    void aMissingKeyIsA422OnTheKey() {
        Response response = request().body(BODY).post(path(slug));

        assertEnvelope(response, 422, "VALOR_INVALIDO");
        assertThat(response.jsonPath().getString("field")).isEqualTo(KEY_HEADER);
        assertThat(IdempotencyProbeController.calls(slug)).isZero();
    }

    @Test
    void twoKeyHeaderInstancesAreA422OnTheKey() {
        Response response = request().header(KEY_HEADER, key).header(KEY_HEADER, UUID.randomUUID().toString())
                .body(BODY).post(path(slug));

        assertEnvelope(response, 422, "VALOR_INVALIDO");
        assertThat(response.jsonPath().getString("field")).isEqualTo(KEY_HEADER);
        assertThat(IdempotencyProbeController.calls(slug)).isZero();
    }

    @Test
    void theKeyHeaderNameIsCaseInsensitive() {
        verify(slug, key, BODY);

        Response repeat = request().header("idempotency-key", key).body(BODY).post(path(slug));

        assertThat(repeat.statusCode()).isEqualTo(201);
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
    }

    @Test
    void parallelIdenticalRequestsNeverExecuteTwice() throws Exception {
        IdempotencyProbeController.sleep(slug, 500);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> responses = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) {
                responses.add(pool.submit(() -> {
                    start.await();
                    return verify(slug, key, BODY);
                }));
            }
            start.countDown();
        }

        List<Integer> statuses = new ArrayList<>();
        for (Future<Response> future : responses) {
            Response response = future.get();
            statuses.add(response.statusCode());
            if (response.statusCode() == 503) {
                assertEnvelope(response, 503, "INDISPONIVEL");
                assertThat(response.header("Retry-After")).isEqualTo("1");
            }
        }
        assertThat(statuses).containsAnyOf(201).allMatch(s -> s == 201 || s == 503);
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
    }

    @Test
    void aRequestWhileTheFirstIsRunningIsInProgress() throws Exception {
        IdempotencyProbeController.sleep(slug, 1_500);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Response> first = pool.submit(() -> verify(slug, key, BODY));
            awaitCalls(slug, 1);

            Response second = verify(slug, key, BODY);

            assertEnvelope(second, 503, "INDISPONIVEL");
            assertThat(second.header("Retry-After")).isEqualTo("1");
            assertThat(first.get().statusCode()).isEqualTo(201);
        }
        assertThat(verify(slug, key, BODY).statusCode()).as("a third request replays").isEqualTo(201);
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
    }

    @Test
    void aLeaseLostExceptionRendersAs503WithRetryAfter() {
        Response response = request().post("/public/v/" + slug + "/lease-lost-test");

        assertEnvelope(response, 503, "INDISPONIVEL");
        assertThat(response.header("Retry-After")).isEqualTo("1");
    }

    @Test
    void aFailedExecutionIsNotStoredSoARetryExecutes() {
        IdempotencyProbeController.failOnce(slug);

        assertEnvelope(verify(slug, key, BODY), 500, "ERRO_INTERNO");
        Response retry = verify(slug, key, BODY);

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(2);
    }

    @Test
    void aTransactionalHandlerThatNeverCompletesFailsAndIsNotStored() {
        double before = completionMissing();
        String forgetful = "/public/v/" + slug + "/forgetful-test";
        try (CapturedEvents events = CapturedEvents.of(IdempotencyInterceptor.class)) {
            Response first = request().header(KEY_HEADER, key).body(BODY).post(forgetful);

            assertEnvelope(first, 500, "ERRO_INTERNO");
            assertThat(events.events()).anyMatch(event -> event.getLevel() == Level.ERROR
                    && event.getFormattedMessage().startsWith("event=idempotency_completion_missing"));
        }
        assertThat(completionMissing()).isEqualTo(before + 1);

        Response retry = request().header(KEY_HEADER, key).body(BODY).post(forgetful);

        assertEnvelope(retry, 500, "ERRO_INTERNO");
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(2);
    }

    @Test
    void everyJsonContentTypeSpellingIsBufferedAndHashed() {
        Response first = request().header(KEY_HEADER, key).contentType("application/json;charset=UTF-8")
                .body(BODY).post(path(slug));
        Response other = request().header(KEY_HEADER, key).contentType("Application/JSON")
                .body("{\"name\":\"Outra Loja\",\"note\":\"synthetic\"}").post(path(slug));

        assertThat(first.statusCode()).isEqualTo(201);
        assertEnvelope(other, 422, "VALOR_INVALIDO");
        assertThat(IdempotencyProbeController.calls(slug)).isEqualTo(1);
    }

    @Test
    void aNonJsonBodyOnAnIdempotentRouteIs415AndNeverStored() {
        Response response = request().header(KEY_HEADER, key).contentType("text/plain").body("name=x")
                .post(path(slug));

        assertEnvelope(response, 415, "MIDIA_NAO_SUPORTADA");
        assertThat(verify(slug, key, BODY).statusCode()).as("the key is still unused").isEqualTo(201);
    }

    @Test
    void aChunkedJsonBodyOverTheLimitIs413WhileBeingBuffered() throws Exception {
        byte[] oversized = ("{\"name\":\"" + "a".repeat(1_048_576) + "\"}").getBytes(StandardCharsets.UTF_8);
        String head = "POST " + path(slug) + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json"
                + "\r\nTransfer-Encoding: chunked\r\n" + KEY_HEADER + ": " + key + "\r\n" + BFF_KEY_HEADER + ": " + bffKey();

        RawHttp.Response response = RawHttp.send(port, head.getBytes(StandardCharsets.ISO_8859_1), chunked(oversized));

        assertThat(response.status()).as(response.toString()).isEqualTo(413);
        assertThat(response.body()).startsWith("{\"code\":\"REQUISICAO_GRANDE\"");
        assertThat(IdempotencyProbeController.calls(slug)).isZero();
    }

    private static byte[] chunked(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int chunk = 64 * 1024;
        for (int sent = 0; sent < body.length; sent += chunk) {
            int length = Math.min(chunk, body.length - sent);
            out.writeBytes((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(body, sent, length);
            out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        out.writeBytes("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private Response verify(String targetSlug, String idempotencyKey, String body) {
        return request().header(KEY_HEADER, idempotencyKey).body(body).post(path(targetSlug));
    }

    private RequestSpecification request() {
        return http().contentType("application/json");
    }

    private static String path(String targetSlug) {
        return "/public/v/" + targetSlug + "/verify-test";
    }

    private double completionMissing() {
        Counter counter = meters.find("kybr.idempotency.completion_missing").counter();
        return counter == null ? 0 : counter.count();
    }

    private static void assertEnvelope(Response response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.jsonPath().getString("code")).isEqualTo(code);
    }

    private static void awaitCalls(String targetSlug, int calls) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (IdempotencyProbeController.calls(targetSlug) < calls && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(IdempotencyProbeController.calls(targetSlug)).isEqualTo(calls);
    }
}
