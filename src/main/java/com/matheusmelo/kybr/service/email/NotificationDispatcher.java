package com.matheusmelo.kybr.service.email;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.ResultadoEnvio;
import com.matheusmelo.kybr.model.link.TipoOutbox;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.OutboxClaims;
import com.matheusmelo.kybr.service.crypto.FieldCipher;
import com.matheusmelo.kybr.service.crypto.FieldDecryptException;
import com.matheusmelo.kybr.service.jobs.BackgroundJob;
import com.matheusmelo.kybr.service.jobs.JobName;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Delivers the outbox's notification e-mails ({@code kybr.email.dispatcher}, every 10 s). Each run
 * claims due events one at a time (lease and owner fence, so two machines never send one event
 * twice), renders and sends through {@link EmailService} with the event id as the idempotency key,
 * and then:
 *
 * <ul>
 *   <li>sent → processed ({@code resultado_envio=enviado});
 *   <li>capped → processed ({@code limitado}), never retried;
 *   <li>a permanent failure → failed at once, {@code kybr.email.failed{motivo=permanente}};
 *   <li>a retryable failure → due again after the next backoff step (1 min, 10 min, 1 h); once
 *       they're used up, failed with {@code motivo=temporario}.
 * </ul>
 *
 * <p>Only enabled event types are claimed; the others stay pending and expire. The contact-change
 * notice ({@code kybr.email.events.contato-alterado.enabled}, off by default) goes to the previous
 * address, decrypted only in memory for the send, and shows the new address masked only. Turning it
 * on without its template fails startup. No address is ever logged.
 */
@Component
public class NotificationDispatcher implements BackgroundJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    /** The contact-change notice's template; ships only once its copy is approved. */
    public static final String CONTATO_ALTERADO_TEMPLATE = "contato-alterado";
    static final String FAILED_METRIC = "kybr.email.failed";
    /** The most events one run handles, so a run always ends. */
    private static final int MAX_PER_RUN = 100;

    /**
     * @param contatoAlteradoEnabled whether contact-change notices are sent
     * @param lease how long a claim holds an event
     * @param backoff the wait before each retry, in order
     */
    record Settings(boolean contatoAlteradoEnabled, Duration lease, List<Duration> backoff) {
        Settings {
            backoff = List.copyOf(backoff);
        }

        static Settings of(KybrProperties.Email email) {
            return new Settings(email.events().contatoAlterado().enabled(), email.dispatcher().lease(),
                    email.dispatcher().backoff());
        }
    }

    private final OutboxClaims claims;
    private final LinkCaseRepository cases;
    private final FieldCipher cipher;
    private final EmailService emails;
    private final Settings settings;
    private final Set<TipoOutbox> enabled;
    private final Clock clock;
    private final MeterRegistry meters;

    @Autowired
    public NotificationDispatcher(OutboxClaims claims, LinkCaseRepository cases, FieldCipher cipher,
            EmailService emails, TemplateRenderer renderer, KybrProperties properties, Clock clock,
            MeterRegistry meters) {
        this(claims, cases, cipher, emails, renderer, Settings.of(properties.email()), clock, meters);
    }

    NotificationDispatcher(OutboxClaims claims, LinkCaseRepository cases, FieldCipher cipher, EmailService emails,
            TemplateRenderer renderer, Settings settings, Clock clock, MeterRegistry meters) {
        if (settings.contatoAlteradoEnabled() && !renderer.has(CONTATO_ALTERADO_TEMPLATE)) {
            throw new IllegalStateException("kybr.email.events.contato-alterado.enabled is true, but there is no "
                    + CONTATO_ALTERADO_TEMPLATE + " template: it stays off until its copy is approved");
        }
        this.claims = claims;
        this.cases = cases;
        this.cipher = cipher;
        this.emails = emails;
        this.settings = settings;
        this.enabled = settings.contatoAlteradoEnabled() ? EnumSet.of(TipoOutbox.CONTATO_ALTERADO)
                : EnumSet.noneOf(TipoOutbox.class);
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public JobName name() {
        return JobName.DISPATCHER;
    }

    @Override
    public void runOnce() {
        if (enabled.isEmpty()) {
            return;
        }
        String owner = UUID.randomUUID().toString();
        for (int handled = 0; handled < MAX_PER_RUN; handled++) {
            Optional<OutboxEvent> claimed = claims.claimNext(enabled, now(), owner, settings.lease());
            if (claimed.isEmpty()) {
                return;
            }
            deliver(claimed.get(), owner);
        }
    }

    private void deliver(OutboxEvent event, String owner) {
        String template = template(event.tipo());
        if (event.tentativas() > settings.backoff().size() + 1) {
            // earlier runs claimed it and died before deciding; stop instead of re-claiming it for 30 days
            fail(event, owner, template, "temporario");
            return;
        }
        try {
            send(event, owner, template);
        } catch (RuntimeException e) {
            log.warn("event=email_error tipo={} exception={} outbox_id={}", template, e.getClass().getSimpleName(),
                    event.id());
            retryOrFail(event, owner, template);
        }
    }

    private void send(OutboxEvent event, String owner, String template) {
        Optional<LinkCase> linkCase = cases.findById(event.caseId());
        if (linkCase.isEmpty() || linkCase.get().contato() == null) {
            fail(event, owner, template, "sem_contato");
            return;
        }
        String previousAddress;
        try {
            previousAddress = cipher.decrypt(event.payloadCifrado(), event.id());
        } catch (FieldDecryptException e) {
            fail(event, owner, template, "ilegivel");
            return;
        }
        Map<String, TemplateValue> values = Map.of(
                "email_novo", new TemplateValue.EmailMascarado(linkCase.get().contato().emailMascarado()),
                "quando", new TemplateValue.DataHora(linkCase.get().contato().alteradoEm()));
        EmailOutcome outcome = emails.send(template, values, previousAddress, event.id().toString());
        switch (outcome) {
            case EmailOutcome.Sent sent -> claims.markProcessed(event.id(), owner, now(), ResultadoEnvio.enviado);
            case EmailOutcome.Capped capped -> claims.markProcessed(event.id(), owner, now(), ResultadoEnvio.limitado);
            case EmailOutcome.Permanent permanent -> fail(event, owner, template, "permanente");
            case EmailOutcome.Retryable retryable -> retryOrFail(event, owner, template);
        }
    }

    private void retryOrFail(OutboxEvent event, String owner, String template) {
        int attempts = event.tentativas();
        if (attempts > settings.backoff().size()) {
            fail(event, owner, template, "temporario");
            return;
        }
        claims.reschedule(event.id(), owner, now().plus(settings.backoff().get(attempts - 1)));
    }

    private void fail(OutboxEvent event, String owner, String template, String motivo) {
        if (claims.markFailed(event.id(), owner, now())) {
            meters.counter(FAILED_METRIC, "tipo", template, "motivo", motivo).increment();
            log.warn("event=email_failed tipo={} motivo={} outbox_id={}", template, motivo, event.id());
        }
    }

    private static String template(TipoOutbox tipo) {
        return switch (tipo) {
            case CONTATO_ALTERADO -> CONTATO_ALTERADO_TEMPLATE;
        };
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
