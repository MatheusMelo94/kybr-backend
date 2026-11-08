package com.matheusmelo.kybr.service.email;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.link.TokenSlot;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The operator's test e-mail: fixed internal copy (not seller-facing), sent only to an address on
 * {@code kybr.email.test-recipients}, to check delivery and the domain's DKIM, SPF and DMARC
 * alignment before go-live. Each send uses a fresh idempotency key and, once handed to the
 * provider, is audited with the operator as actor and the admin token slot used. The address is
 * never logged or audited.
 */
@Service
public class OperatorEmailService {

    /** The test e-mail's template. */
    public static final String TEMPLATE = "teste-operador";

    public enum Result {
        /** Handed to the provider, and audited. */
        SENT,
        /** The address isn't on the allowed list; nothing was sent. */
        NOT_ALLOWED,
        /** Capped, or the provider refused or failed; nothing was audited. */
        NOT_SENT
    }

    private final EmailService emails;
    private final AuditEventRepository audits;
    private final List<String> testRecipients;
    private final Clock clock;

    @Autowired
    public OperatorEmailService(EmailService emails, AuditEventRepository audits, KybrProperties properties,
            Clock clock) {
        this(emails, audits, properties.email().testRecipients(), clock);
    }

    OperatorEmailService(EmailService emails, AuditEventRepository audits, List<String> testRecipients, Clock clock) {
        this.emails = emails;
        this.audits = audits;
        this.testRecipients = testRecipients.stream().map(OperatorEmailService::normalise).toList();
        this.clock = clock;
    }

    public Result sendTest(String to, TokenSlot slot) {
        if (to == null || !testRecipients.contains(normalise(to))) {
            return Result.NOT_ALLOWED;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        EmailOutcome outcome = emails.send(TEMPLATE, Map.of("quando", new TemplateValue.DataHora(now)), normalise(to),
                UUID.randomUUID().toString());
        if (!(outcome instanceof EmailOutcome.Sent)) {
            return Result.NOT_SENT;
        }
        audits.insert(new AuditEvent(UUID.randomUUID(), null, TipoAuditoria.EMAIL_TESTE_ENVIADO, Ator.operador, null,
                now, null, true, null, slot, null));
        return Result.SENT;
    }

    private static String normalise(String address) {
        return address.strip().toLowerCase(Locale.ROOT);
    }
}
