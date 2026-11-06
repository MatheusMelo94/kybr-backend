package com.matheusmelo.kybr.support.qa;

import com.matheusmelo.kybr.dto.validation.CnpjCanonico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acceptance probe for CNPJ handler wiring: the production
 * {@link CnpjCanonico} constraint in a body, a query parameter and a path variable, with an
 * invocation counter (a rejected value must never reach the handler).
 */
@RestController
public class QaCnpjProbeController {

    public static final AtomicInteger INVOCATIONS = new AtomicInteger();

    public record CnpjBody(@NotNull @CnpjCanonico String cnpj) {
    }

    @PostMapping("/test/qa/cnpj")
    Map<String, String> body(@Valid @RequestBody CnpjBody body) {
        INVOCATIONS.incrementAndGet();
        return Map.of("cnpj", body.cnpj());
    }

    @GetMapping("/test/qa/cnpj")
    Map<String, String> query(@RequestParam("cnpj") @CnpjCanonico String cnpj) {
        INVOCATIONS.incrementAndGet();
        return Map.of("cnpj", cnpj);
    }

    @GetMapping("/test/qa/cnpj/{cnpj}")
    Map<String, String> path(@PathVariable("cnpj") @CnpjCanonico String cnpj) {
        INVOCATIONS.incrementAndGet();
        return Map.of("cnpj", cnpj);
    }
}
