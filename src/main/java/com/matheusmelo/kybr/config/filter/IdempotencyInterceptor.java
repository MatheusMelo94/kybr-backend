package com.matheusmelo.kybr.config.filter;

import com.matheusmelo.kybr.config.IndexDefinitionProvider;
import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.MetricTags;
import com.matheusmelo.kybr.service.idempotency.IdempotencyAttempt;
import com.matheusmelo.kybr.service.idempotency.IdempotencyCompletion;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService.Begin;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService.Execute;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService.Replay;
import com.matheusmelo.kybr.service.idempotency.Idempotent;
import com.matheusmelo.kybr.util.RequestHash;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * The idempotency check for {@link Idempotent} handlers. It runs before argument
 * resolution and validation, so a replay beats validation and any business error.
 *
 * <ol>
 *   <li>Exactly one {@code Idempotency-Key} header, else 422 {@code VALOR_INVALIDO} on the key
 *       (two instances or a comma-joined value are rejected).
 *   <li>The body is the one filter [8] buffered; a non-JSON body is 415 here, before anything is
 *       recorded, so a body that was never hashed can't be replayed.
 *   <li>{@link IdempotencyService#begin} over {@link RequestHash} (method, route template, binding,
 *       body): a replay writes the stored status, content type and bytes and the controller never
 *       runs; otherwise the attempt goes in {@link #ATTEMPT_ATTRIBUTE} and response capture starts.
 *   <li>After completion, the attempt is completed with the captured response (storable outcomes
 *       kept, others deleted), unless the handler already finished it
 *       ({@code completeInTransaction}); an exception that escaped abandons it.
 * </ol>
 *
 * <p>Errors thrown here (mismatch, in progress, lease lost, invalid key) render through the global
 * exception handler. A handler whose {@link Idempotent#binding()} has no bean, or a
 * {@link IdempotencyCompletion#UNIQUE_KEY} handler whose collection declares no unique
 * {@code idempotency_key} index, fails startup, not its first request.
 *
 * <p>A {@link IdempotencyCompletion#TRANSACTIONAL} handler that returns a 2xx without finishing
 * its attempt is a programming error: the record is deleted (a retry re-executes rather than
 * replaying an unprotected result), {@code event=idempotency_completion_missing} is logged and
 * {@value #COMPLETION_MISSING_METRIC} increments. In the {@code test} profile the request also
 * fails with a 500, so the endpoint's own test catches it; the response is held uncommitted there
 * for that (bodies under the container's buffer size).
 *
 * <p>A handler that names an {@link Idempotent#outcomeMetric()} has its replays counted there as
 * {@code replayed} and every 4xx, raised here or later, as {@code rejected}.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);

    /** The request attribute holding the current {@link IdempotencyAttempt}, for completeInTransaction. */
    public static final String ATTEMPT_ATTRIBUTE = IdempotencyInterceptor.class.getName() + ".attempt";

    public static final String COMPLETION_MISSING_METRIC = "kybr.idempotency.completion_missing";

    static final String REPLAYED = "replayed";
    static final String REJECTED = "rejected";

    private static final String KEY_FIELD = "idempotency_key";

    private final IdempotencyService service;
    private final JsonMapper jsonMapper;
    private final BeanFactory beans;
    private final MeterRegistry meters;
    private final ErrorResponseWriter errors;
    private final boolean failMissingCompletion;

    public IdempotencyInterceptor(IdempotencyService service, JsonMapper jsonMapper, BeanFactory beans,
            MeterRegistry meters, ErrorResponseWriter errors, Environment environment) {
        this.service = service;
        this.jsonMapper = jsonMapper;
        this.beans = beans;
        this.meters = meters;
        this.errors = errors;
        this.failMissingCompletion = environment.acceptsProfiles(Profiles.of("test"));
    }

    /** Every handler mapping is initialised by now: check each {@link Idempotent} handler's declaration once. */
    @Override
    public void afterSingletonsInstantiated() {
        beans.getBeanProvider(RequestMappingInfoHandlerMapping.class).orderedStream()
                .flatMap(mapping -> mapping.getHandlerMethods().values().stream())
                .filter(handler -> handler.hasMethodAnnotation(Idempotent.class))
                .forEach(handler -> {
                    requireBindingBean(handler);
                    requireUniqueKeyIndex(handler);
                });
    }

    private void requireBindingBean(HandlerMethod handler) {
        Idempotent idempotent = handler.getMethodAnnotation(Idempotent.class);
        try {
            beans.getBean(idempotent.binding());
        } catch (BeansException e) {
            throw new IllegalStateException("@Idempotent handler " + name(handler) + " names binding "
                    + idempotent.binding().getName() + ", which is not a single bean", e);
        }
    }

    private void requireUniqueKeyIndex(HandlerMethod handler) {
        Idempotent idempotent = handler.getMethodAnnotation(Idempotent.class);
        if (idempotent.completion() != IdempotencyCompletion.UNIQUE_KEY) {
            return;
        }
        String collection = idempotent.uniqueKeyCollection();
        if (collection.isBlank()) {
            throw new IllegalStateException("@Idempotent handler " + name(handler)
                    + " completes by UNIQUE_KEY but names no uniqueKeyCollection");
        }
        boolean declared = beans.getBeanProvider(IndexDefinitionProvider.class).orderedStream()
                .filter(provider -> provider.collection().equals(collection))
                .flatMap(provider -> provider.indexes().stream())
                .anyMatch(index -> index.getIndexKeys().keySet().equals(Set.of(KEY_FIELD))
                        && Boolean.TRUE.equals(index.getIndexOptions().get("unique")));
        if (!declared) {
            throw new IllegalStateException("@Idempotent handler " + name(handler) + " completes by UNIQUE_KEY, but "
                    + collection + " declares no unique index on " + KEY_FIELD);
        }
    }

    private static String name(HandlerMethod handler) {
        return handler.getBeanType().getName() + "#" + handler.getMethod().getName();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Idempotent idempotent = handler instanceof HandlerMethod method ? method.getMethodAnnotation(Idempotent.class)
                : null;
        if (idempotent == null) {
            return true;
        }
        try {
            return begin(request, response, idempotent);
        } catch (ApiException e) {
            if (e.code().status().is4xxClientError()) {
                countOutcome(idempotent, request, REJECTED);
            }
            throw e;
        }
    }

    private boolean begin(HttpServletRequest request, HttpServletResponse response, Idempotent idempotent)
            throws IOException {
        List<String> keys = Collections.list(request.getHeaders(IdempotencyService.KEY_HEADER));
        if (keys.size() != 1) {
            throw new ApiException(ErrorCode.VALOR_INVALIDO, IdempotencyService.KEY_HEADER);
        }
        byte[] body = body(request);
        String binding = beans.getBean(idempotent.binding()).resolve(uriVariables(request));
        String route = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String hash = RequestHash.of(jsonMapper, request.getMethod(), route, binding, body);

        Begin begin = service.begin(idempotent.profile(), binding, keys.getFirst(), hash);
        if (begin instanceof Replay replay) {
            countOutcome(idempotent, request, REPLAYED);
            replay(response, replay);
            return false;
        }
        CachedBodyFilter.CapturingResponse capture = CachedBodyFilter.capturingResponse(response);
        if (capture == null) {
            throw new IllegalStateException("CachedBodyFilter must wrap @Idempotent requests");
        }
        request.setAttribute(ATTEMPT_ATTRIBUTE, ((Execute) begin).attempt());
        capture.startCapture();
        if (failMissingCompletion && idempotent.completion() == IdempotencyCompletion.TRANSACTIONAL) {
            capture.holdCommit();
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        if (ex == null && response.getStatus() >= 400 && response.getStatus() < 500
                && handler instanceof HandlerMethod method && method.hasMethodAnnotation(Idempotent.class)) {
            countOutcome(method.getMethodAnnotation(Idempotent.class), request, REJECTED);
        }
        if (!(request.getAttribute(ATTEMPT_ATTRIBUTE) instanceof IdempotencyAttempt attempt) || attempt.isFinished()) {
            return;
        }
        if (ex != null) {
            service.abandon(attempt);
            return;
        }
        int status = response.getStatus();
        if (status >= 200 && status < 300 && handler instanceof HandlerMethod method
                && method.getMethodAnnotation(Idempotent.class).completion() == IdempotencyCompletion.TRANSACTIONAL) {
            completionMissing(attempt, method, response);
            return;
        }
        CachedBodyFilter.CapturingResponse capture = CachedBodyFilter.capturingResponse(response);
        try {
            service.complete(attempt, response.getStatus(), response.getContentType(), capture.captured());
        } catch (ApiException e) {
            // The response is already on its way; a retry replays the winner or re-executes.
            log.warn("event=idempotency_complete_failed code={}", e.code());
        }
    }

    private void completionMissing(IdempotencyAttempt attempt, HandlerMethod handler, HttpServletResponse response) {
        service.abandon(attempt);
        log.error("event=idempotency_completion_missing handler={}", name(handler));
        meters.counter(COMPLETION_MISSING_METRIC).increment();
        if (failMissingCompletion && !response.isCommitted()) {
            try {
                response.resetBuffer();
                errors.write(response, ErrorCode.ERRO_INTERNO);
            } catch (IOException e) {
                log.warn("event=idempotency_completion_missing_write_failed error={}", e.getClass().getSimpleName());
            }
        }
    }

    private static byte[] body(HttpServletRequest request) {
        return CachedBodyFilter.cachedBody(request).orElseGet(() -> {
            if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
                throw new ApiException(ErrorCode.MIDIA_NAO_SUPORTADA);
            }
            return new byte[0];
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> uriVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    /** Counts on the handler's outcome metric, tagged with the resolved link when there is one. */
    private void countOutcome(Idempotent idempotent, HttpServletRequest request, String resultado) {
        if (idempotent.outcomeMetric().isEmpty()) {
            return;
        }
        Workspace workspace = request.getAttribute(LinkSlugInterceptor.WORKSPACE_ATTRIBUTE) instanceof Workspace w
                ? w : null;
        meters.counter(idempotent.outcomeMetric(), MetricTags.SLUG, MetricTags.slug(workspace), "resultado",
                resultado).increment();
    }

    private static void replay(HttpServletResponse response, Replay replay) throws IOException {
        response.setStatus(replay.status());
        if (replay.contentType() != null) {
            response.setContentType(replay.contentType());
        }
        response.setContentLength(replay.body().length);
        response.getOutputStream().write(replay.body());
    }
}
