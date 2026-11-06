package com.matheusmelo.kybr.support.logging;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Test-only route shaped like the public case page: a slug and a case token in the path. */
@RestController
public class AccessLogProbeController {

    @GetMapping("/public/v/{slug}/cases/{token}")
    Map<String, Boolean> caseStatus(@PathVariable String slug, @PathVariable String token) {
        return Map.of("ok", true);
    }
}
