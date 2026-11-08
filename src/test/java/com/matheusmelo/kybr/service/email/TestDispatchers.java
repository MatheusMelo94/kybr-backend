package com.matheusmelo.kybr.service.email;

import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.OutboxClaims;
import com.matheusmelo.kybr.service.crypto.FieldCipher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Builds a dispatcher with the address-change notice switched on, for tests outside this package.
 * The test profile keeps the notice off, so the context's own dispatcher never sends it.
 */
public final class TestDispatchers {

    private TestDispatchers() {
    }

    public static NotificationDispatcher withNoticeOn(OutboxClaims claims, LinkCaseRepository cases, FieldCipher cipher,
            EmailService emails, Clock clock, MeterRegistry meters) {
        return new NotificationDispatcher(claims, cases, cipher, emails, new TemplateRenderer(),
                new NotificationDispatcher.Settings(true, Duration.ofSeconds(60), List.of(Duration.ofMinutes(1))),
                clock, meters);
    }
}
