package com.matheusmelo.kybr.support.web;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints for the web pipeline. Never on the production classpath. */
@RestController
@RequestMapping("/test/web")
public class WebProbeController {

    public static final AtomicInteger INVOCATIONS = new AtomicInteger();

    @GetMapping("/ok")
    Map<String, String> ok() {
        INVOCATIONS.incrementAndGet();
        return Map.of("status", "ok");
    }

    @GetMapping("/client-error")
    Map<String, String> clientError() {
        INVOCATIONS.incrementAndGet();
        throw new ApiException(ErrorCode.VALOR_INVALIDO, "probe");
    }

    @GetMapping("/redirect")
    void redirect(HttpServletResponse response) throws IOException {
        INVOCATIONS.incrementAndGet();
        response.sendRedirect("/test/web/ok");
    }

    @GetMapping("/found")
    ResponseEntity<Void> found() {
        INVOCATIONS.incrementAndGet();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/test/web/ok")).build();
    }

    @GetMapping("/cookie")
    Map<String, String> cookie(HttpServletResponse response) {
        INVOCATIONS.incrementAndGet();
        response.addCookie(new Cookie("probe", "1"));
        response.addHeader(HttpHeaders.SET_COOKIE, "probe2=1");
        return Map.of("status", "ok");
    }

    @GetMapping("/session")
    Map<String, String> session(HttpServletRequest request) {
        INVOCATIONS.incrementAndGet();
        request.getSession();
        return Map.of("status", "ok");
    }

    /** Default body limit (1 MiB). */
    @PostMapping("/echo")
    Map<String, Integer> echo(@RequestBody byte[] body) {
        INVOCATIONS.incrementAndGet();
        return Map.of("size", body.length);
    }

    /** Raised to 3 MiB by WebIntegrationTest's route-limit property. */
    @PostMapping("/large")
    Map<String, Integer> large(@RequestBody byte[] body) {
        INVOCATIONS.incrementAndGet();
        return Map.of("size", body.length);
    }

    @GetMapping("/server-error")
    Map<String, String> serverError() {
        INVOCATIONS.incrementAndGet();
        throw new IllegalStateException("probe failure");
    }
}
