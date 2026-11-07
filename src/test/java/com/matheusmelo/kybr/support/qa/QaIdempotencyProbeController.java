package com.matheusmelo.kybr.support.qa;

import com.matheusmelo.kybr.config.filter.IdempotencyInterceptor;
import com.matheusmelo.kybr.service.idempotency.IdempotencyAttempt;
import com.matheusmelo.kybr.service.idempotency.IdempotencyCompletion;
import com.matheusmelo.kybr.service.idempotency.IdempotencyProfile;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService;
import com.matheusmelo.kybr.service.idempotency.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import com.matheusmelo.kybr.service.idempotency.SignupBindingResolver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acceptance probe: {@code PANEL}-profile idempotent routes with the
 * constant {@code signup} binding. Per-scenario invocation counters and a status toggle, so each
 * test uses its own scenario and never sees another test's state. The body carries a canary that
 * must never reach the logs.
 */
@RestController
public class QaIdempotencyProbeController {

    public static final String BODY_CANARY_PREFIX = "qa-replay-body-canary-";

    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> STATUS = new ConcurrentHashMap<>();

    private final IdempotencyService idempotency;

    public QaIdempotencyProbeController(IdempotencyService idempotency) {
        this.idempotency = idempotency;
    }

    @PostMapping("/test/qa/idem/{scenario}")
    @Idempotent(profile = IdempotencyProfile.PANEL, binding = SignupBindingResolver.class,
            completion = IdempotencyCompletion.TRANSACTIONAL)
    ResponseEntity<Map<String, Object>> panel(@PathVariable String scenario, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return respond(scenario, request);
    }

    /** A second idempotent route with the same profile and binding: the route is part of the hash. */
    @PostMapping("/test/qa/idem-other/{scenario}")
    @Idempotent(profile = IdempotencyProfile.PANEL, binding = SignupBindingResolver.class,
            completion = IdempotencyCompletion.TRANSACTIONAL)
    ResponseEntity<Map<String, Object>> other(@PathVariable String scenario, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return respond(scenario, request);
    }

    /** A 2xx completes in a transaction, as the declared mode requires; any other status completes after the response. */
    private ResponseEntity<Map<String, Object>> respond(String scenario, HttpServletRequest request) {
        int call = CALLS.computeIfAbsent(scenario, s -> new AtomicInteger()).incrementAndGet();
        int status = STATUS.getOrDefault(scenario, 201);
        Map<String, Object> body = Map.of("call", call, "canary", BODY_CANARY_PREFIX + scenario);
        if (status >= 200 && status < 300) {
            IdempotencyAttempt attempt = (IdempotencyAttempt) request.getAttribute(IdempotencyInterceptor.ATTEMPT_ATTRIBUTE);
            idempotency.completeInTransaction(attempt, status, () -> body);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    public static int calls(String scenario) {
        AtomicInteger calls = CALLS.get(scenario);
        return calls == null ? 0 : calls.get();
    }

    public static void status(String scenario, int status) {
        STATUS.put(scenario, status);
    }

    public static void clearStatus(String scenario) {
        STATUS.remove(scenario);
    }
}
