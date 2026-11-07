package com.matheusmelo.kybr.support.link;

import com.matheusmelo.kybr.config.filter.IdempotencyInterceptor;
import com.matheusmelo.kybr.config.filter.LinkSlugInterceptor;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.idempotency.IdempotencyAttempt;
import com.matheusmelo.kybr.service.idempotency.IdempotencyCompletion;
import com.matheusmelo.kybr.service.idempotency.IdempotencyProfile;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService;
import com.matheusmelo.kybr.service.idempotency.Idempotent;
import com.matheusmelo.kybr.service.idempotency.SlugBindingResolver;
import com.matheusmelo.kybr.service.link.LinkRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Test-only link routes: they echo the workspace the slug interceptor resolved. */
@RestController
public class LinkSlugProbeController {

    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();

    private final IdempotencyService idempotency;

    public LinkSlugProbeController(IdempotencyService idempotency) {
        this.idempotency = idempotency;
    }

    public record ProbeBody(@NotBlank String name) {
    }

    public record ProbeResponse(String slug, int call) {
    }

    @LinkRoute
    @GetMapping("/public/v/{slug}/slug-probe")
    ProbeResponse active(@RequestAttribute(LinkSlugInterceptor.WORKSPACE_ATTRIBUTE) Workspace workspace) {
        return new ProbeResponse(workspace.slug(), count(workspace));
    }

    @LinkRoute(readableWhenDisabled = true)
    @GetMapping("/public/v/{slug}/slug-probe-readable")
    ProbeResponse readable(@RequestAttribute(LinkSlugInterceptor.WORKSPACE_ATTRIBUTE) Workspace workspace) {
        return new ProbeResponse(workspace.slug(), count(workspace));
    }

    @LinkRoute
    @PostMapping("/public/v/{slug}/slug-probe-verify")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(profile = IdempotencyProfile.PUBLIC_VERIFY, binding = SlugBindingResolver.class,
            completion = IdempotencyCompletion.TRANSACTIONAL)
    ProbeResponse verify(@RequestAttribute(LinkSlugInterceptor.WORKSPACE_ATTRIBUTE) Workspace workspace,
            @Valid @RequestBody ProbeBody body, HttpServletRequest request) {
        IdempotencyAttempt attempt = (IdempotencyAttempt) request.getAttribute(IdempotencyInterceptor.ATTEMPT_ATTRIBUTE);
        return idempotency.completeInTransaction(attempt, 201,
                () -> new ProbeResponse(workspace.slug(), count(workspace)));
    }

    public static int calls(String slug) {
        AtomicInteger calls = CALLS.get(slug);
        return calls == null ? 0 : calls.get();
    }

    private static int count(Workspace workspace) {
        return CALLS.computeIfAbsent(workspace.slug(), s -> new AtomicInteger()).incrementAndGet();
    }
}
