package com.matheusmelo.kybr.support.qa;

import com.matheusmelo.kybr.config.KybrProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acceptance probes: a handler that fails with secrets in its exception
 * message, and one that returns the time types.
 */
@RestController
public class QaThrowingController {

    private final KybrProperties properties;

    public QaThrowingController(KybrProperties properties) {
        this.properties = properties;
    }

    /**
     * Throws with a bearer token, a case-token path and the configured BFF key in the message (the
     * redactor is the backstop that must catch all three) and a canary that must not reach the body.
     */
    @GetMapping("/test/qa/throw/{canary}")
    Map<String, String> fail(@PathVariable String canary) {
        throw new IllegalStateException("qa failure Authorization: Bearer " + canary + "-bearer"
                + " while calling /public/v/s/cases/" + canary + "-token/contact with key "
                + properties.bff().current() + " canary=" + canary);
    }

    @GetMapping("/test/qa/time")
    Map<String, Object> time() {
        return Map.of(
                "instant", Instant.parse("2030-01-02T03:04:05Z"),
                "offset_date_time", OffsetDateTime.of(2030, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(-3)),
                "local_date", LocalDate.of(2030, 1, 2));
    }
}
