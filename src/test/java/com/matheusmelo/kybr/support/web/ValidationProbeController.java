package com.matheusmelo.kybr.support.web;

import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.RateLimitExceededException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.bson.BsonDocument;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints for exception mapping. */
@RestController
@RequestMapping("/test/web/validation")
public class ValidationProbeController {

    /** A synthetic e-mail that must never reach the logs. */
    public static final String CANARY_EMAIL = "canary.person@example.invalid";

    public record ProbeBody(@NotBlank String displayName, @ProbeCnpj String cnpj, Integer itemCount,
            @Valid Nested nested) {
    }

    public record Nested(@Size(max = 3) String shortCode) {
    }

    @PostMapping("/body")
    Map<String, String> body(@Valid @RequestBody ProbeBody body) {
        return Map.of("status", "ok");
    }

    @GetMapping("/query")
    Map<String, String> query(@RequestParam("cnpj") @ProbeCnpj String cnpj,
            @RequestParam(name = "name", required = false) @Size(max = 3) String name) {
        return Map.of("status", "ok");
    }

    @GetMapping("/path/{cnpj}")
    Map<String, String> path(@PathVariable("cnpj") @ProbeCnpj String cnpj) {
        return Map.of("status", "ok");
    }

    @GetMapping("/rate-limited")
    Map<String, String> rateLimited() {
        throw new RateLimitExceededException(ErrorCode.LIMITE_TENTATIVAS, 42);
    }

    @GetMapping("/mongo-down")
    Map<String, String> mongoDown() {
        throw new DataAccessResourceFailureException("connection refused");
    }

    @GetMapping("/mongo-timeout")
    Map<String, String> mongoTimeout() {
        throw new MongoTimeoutException("timed out selecting a server");
    }

    @GetMapping("/mongo-duplicate")
    Map<String, String> mongoDuplicate() {
        MongoWriteException duplicate = new MongoWriteException(
                new WriteError(11000, "E11000 duplicate key error dup key: { email: \"" + CANARY_EMAIL + "\" }",
                        new BsonDocument()),
                new ServerAddress());
        throw new DuplicateKeyException("Write error: " + duplicate.getMessage(), duplicate);
    }
}
