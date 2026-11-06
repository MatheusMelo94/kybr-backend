package com.matheusmelo.kybr.config.filter;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.service.idempotency.IdempotencyAttempt;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService.Begin;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService.Execute;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService.Replay;
import com.matheusmelo.kybr.service.idempotency.Idempotent;
import com.matheusmelo.kybr.util.RequestHash;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
 * exception handler. A handler whose {@link Idempotent#binding()} has no bean fails startup, not
 * its first request.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);

    /** The request attribute holding the current {@link IdempotencyAttempt}, for completeInTransaction. */
    public static final String ATTEMPT_ATTRIBUTE = IdempotencyInterceptor.class.getName() + ".attempt";

    private final IdempotencyService service;
    private final JsonMapper jsonMapper;
    private final BeanFactory beans;

    public IdempotencyInterceptor(IdempotencyService service, JsonMapper jsonMapper, BeanFactory beans) {
        this.service = service;
        this.jsonMapper = jsonMapper;
        this.beans = beans;
    }

    /** Every handler mapping is initialised by now: resolve each {@link Idempotent} handler's binding bean once. */
    @Override
    public void afterSingletonsInstantiated() {
        beans.getBeanProvider(RequestMappingInfoHandlerMapping.class).orderedStream()
                .flatMap(mapping -> mapping.getHandlerMethods().values().stream())
                .forEach(this::requireBindingBean);
    }

    private void requireBindingBean(HandlerMethod handler) {
        Idempotent idempotent = handler.getMethodAnnotation(Idempotent.class);
        if (idempotent == null) {
            return;
        }
        try {
            beans.getBean(idempotent.binding());
        } catch (BeansException e) {
            throw new IllegalStateException("@Idempotent handler " + handler.getBeanType().getName() + "#"
                    + handler.getMethod().getName() + " names binding " + idempotent.binding().getName()
                    + ", which is not a single bean", e);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Idempotent idempotent = handler instanceof HandlerMethod method ? method.getMethodAnnotation(Idempotent.class)
                : null;
        if (idempotent == null) {
            return true;
        }
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
            replay(response, replay);
            return false;
        }
        CachedBodyFilter.CapturingResponse capture = CachedBodyFilter.capturingResponse(response);
        if (capture == null) {
            throw new IllegalStateException("CachedBodyFilter must wrap @Idempotent requests");
        }
        request.setAttribute(ATTEMPT_ATTRIBUTE, ((Execute) begin).attempt());
        capture.startCapture();
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        if (!(request.getAttribute(ATTEMPT_ATTRIBUTE) instanceof IdempotencyAttempt attempt) || attempt.isFinished()) {
            return;
        }
        if (ex != null) {
            service.abandon(attempt);
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

    private static void replay(HttpServletResponse response, Replay replay) throws IOException {
        response.setStatus(replay.status());
        if (replay.contentType() != null) {
            response.setContentType(replay.contentType());
        }
        response.setContentLength(replay.body().length);
        response.getOutputStream().write(replay.body());
    }
}
