package com.matheusmelo.kybr.support.idempotency;

import com.matheusmelo.kybr.config.filter.IdempotencyInterceptor;
import com.matheusmelo.kybr.service.idempotency.IdempotencyAttempt;
import com.matheusmelo.kybr.service.idempotency.IdempotencyCompletion;
import com.matheusmelo.kybr.service.idempotency.IdempotencyLeaseLostException;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService;
import com.matheusmelo.kybr.service.idempotency.Idempotent;
import com.matheusmelo.kybr.service.idempotency.IdempotencyProfile;
import com.matheusmelo.kybr.service.idempotency.SlugBindingResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only idempotent routes. Per-slug invocation counters and toggles, so each
 * test uses its own slug and never sees another test's state.
 */
@RestController
public class IdempotencyProbeController {

    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final Map<String, Long> SLEEP_MILLIS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> FAIL_ONCE = new ConcurrentHashMap<>();
    private static volatile boolean strict;

    private final IdempotencyService idempotency;

    public IdempotencyProbeController(IdempotencyService idempotency) {
        this.idempotency = idempotency;
    }

    public record VerifyRequest(@NotBlank @Strict String name, String note) {
    }

    public record VerifyResponse(String slug, String name, int call) {
    }

    @PostMapping("/public/v/{slug}/verify-test")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(profile = IdempotencyProfile.PUBLIC_VERIFY, binding = SlugBindingResolver.class,
            completion = IdempotencyCompletion.TRANSACTIONAL)
    VerifyResponse verify(@PathVariable String slug, @Valid @RequestBody VerifyRequest request,
            HttpServletRequest servletRequest) throws InterruptedException {
        int call = CALLS.computeIfAbsent(slug, s -> new AtomicInteger()).incrementAndGet();
        Long sleep = SLEEP_MILLIS.get(slug);
        if (sleep != null) {
            Thread.sleep(sleep);
        }
        if (FAIL_ONCE.remove(slug) != null) {
            throw new IllegalStateException("synthetic handler failure");
        }
        IdempotencyAttempt attempt = (IdempotencyAttempt) servletRequest.getAttribute(
                IdempotencyInterceptor.ATTEMPT_ATTRIBUTE);
        return idempotency.completeInTransaction(attempt, 201, () -> new VerifyResponse(slug, request.name(), call));
    }

    /** Declares a transactional completion but never completes: the runtime guard must catch it. */
    @PostMapping("/public/v/{slug}/forgetful-test")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(profile = IdempotencyProfile.PUBLIC_VERIFY, binding = SlugBindingResolver.class,
            completion = IdempotencyCompletion.TRANSACTIONAL)
    VerifyResponse forgetful(@PathVariable String slug, @Valid @RequestBody VerifyRequest request) {
        int call = CALLS.computeIfAbsent(slug, s -> new AtomicInteger()).incrementAndGet();
        return new VerifyResponse(slug, request.name(), call);
    }

    /** Not idempotent: renders a lease-lost exception through the global handler. */
    @PostMapping("/public/v/{slug}/lease-lost-test")
    void leaseLost(@PathVariable String slug) {
        throw new IdempotencyLeaseLostException();
    }

    public static int calls(String slug) {
        AtomicInteger calls = CALLS.get(slug);
        return calls == null ? 0 : calls.get();
    }

    public static void sleep(String slug, long millis) {
        SLEEP_MILLIS.put(slug, millis);
    }

    public static void failOnce(String slug) {
        FAIL_ONCE.put(slug, Boolean.TRUE);
    }

    /** Tightens validation: {@code name} must then be "STRICT" (proves replay precedes validation). */
    public static void strict(boolean on) {
        strict = on;
    }

    public static void reset() {
        SLEEP_MILLIS.clear();
        FAIL_ONCE.clear();
        strict = false;
    }

    @Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = StrictValidator.class)
    public @interface Strict {
        String message() default "probe.strict";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class StrictValidator implements ConstraintValidator<Strict, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return !strict || "STRICT".equals(value);
        }
    }
}
