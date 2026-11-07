package com.matheusmelo.kybr.support.link;

import com.matheusmelo.kybr.dto.validation.CnpjCanonico;
import com.matheusmelo.kybr.dto.validation.PrintableAscii;
import com.matheusmelo.kybr.dto.validation.Responsavel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only body shaped like a verification request, declared in the order cnpj, responsavel, ref,
 * ext_id (not alphabetical), to pin which invalid field is reported first.
 */
@RestController
public class LinkValidationProbeController {

    public record OrderedBody(
            @NotNull(message = "CNPJ_INVALIDO") @CnpjCanonico String cnpj,
            @NotNull(message = "RESPONSAVEL_INVALIDO") @Responsavel String responsavel,
            @PrintableAscii(max = 256) String ref,
            @PrintableAscii(max = 256) String extId) {
    }

    @PostMapping("/test/link/validation")
    Map<String, String> validate(@Valid @RequestBody OrderedBody body) {
        return Map.of("status", "ok");
    }
}
