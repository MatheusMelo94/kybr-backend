package com.matheusmelo.kybr.support.qa;

import com.matheusmelo.kybr.service.idempotency.IdempotencyProfile;
import com.matheusmelo.kybr.service.idempotency.Idempotent;
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

    @PostMapping("/test/qa/idem/{scenario}")
    @Idempotent(profile = IdempotencyProfile.PANEL, binding = SignupBindingResolver.class)
    ResponseEntity<Map<String, Object>> panel(@PathVariable String scenario, @RequestBody Map<String, Object> body) {
        return respond(scenario);
    }

    /** A second idempotent route with the same profile and binding: the route is part of the hash. */
    @PostMapping("/test/qa/idem-other/{scenario}")
    @Idempotent(profile = IdempotencyProfile.PANEL, binding = SignupBindingResolver.class)
    ResponseEntity<Map<String, Object>> other(@PathVariable String scenario, @RequestBody Map<String, Object> body) {
        return respond(scenario);
    }

    private static ResponseEntity<Map<String, Object>> respond(String scenario) {
        int call = CALLS.computeIfAbsent(scenario, s -> new AtomicInteger()).incrementAndGet();
        int status = STATUS.getOrDefault(scenario, 201);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("call", call, "canary", BODY_CANARY_PREFIX + scenario));
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
