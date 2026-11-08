package com.matheusmelo.kybr.service.email;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.service.ratelimit.RateLimitPolicy;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject;
import com.matheusmelo.kybr.service.ratelimit.RateLimiter;
import com.matheusmelo.kybr.service.ratelimit.SubjectKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one way to send an e-mail: render the template, check the per-address caps, then hand the
 * message to the sender. A capped message is dropped (never queued) and counted in
 * {@code kybr.email.capped}; a sent one in {@code kybr.email.sent}, both tagged with the template.
 *
 * <p>Caps count per address, by the HMAC of its lower-cased form ({@link SubjectKind#EMAIL}): at
 * most {@code per-hour} in a clock hour and {@code per-day} in a UTC day. A send can't be rolled
 * back, so calling this inside a transaction is refused. The recipient is never logged.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final TemplateRenderer renderer;
    private final RateLimiter limiter;
    private final EmailSender sender;
    private final MeterRegistry meters;
    private final RateLimitPolicy perHour;
    private final RateLimitPolicy perDay;

    @Autowired
    public EmailService(TemplateRenderer renderer, RateLimiter limiter, EmailSender sender, KybrProperties properties,
            MeterRegistry meters) {
        this(renderer, limiter, sender, properties.email().caps(), meters);
    }

    public EmailService(TemplateRenderer renderer, RateLimiter limiter, EmailSender sender,
            KybrProperties.Email.Caps caps, MeterRegistry meters) {
        this.renderer = renderer;
        this.limiter = limiter;
        this.sender = sender;
        this.meters = meters;
        this.perHour = new RateLimitPolicy("email.address.hour", caps.perHour(), Duration.ofHours(1),
                ErrorCode.LIMITE_TENTATIVAS, SubjectKind.EMAIL);
        this.perDay = new RateLimitPolicy("email.address.day", caps.perDay(), Duration.ofDays(1),
                ErrorCode.LIMITE_TENTATIVAS, SubjectKind.EMAIL);
    }

    /**
     * @param idempotencyKey the provider-side deduplication key (an outbox event's id)
     * @throws IllegalStateException inside a transaction
     */
    public EmailOutcome send(String template, Map<String, TemplateValue> values, String to, String idempotencyKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("an e-mail is never sent inside a transaction");
        }
        RenderedEmail email = renderer.render(template, values);
        RateLimitSubject subject = new RateLimitSubject.Email(to);
        if (!limiter.peek(perHour, subject).allowed() || !limiter.peek(perDay, subject).allowed()) {
            meters.counter("kybr.email.capped", "tipo", template).increment();
            log.info("event=email_capped tipo={}", template);
            return new EmailOutcome.Capped();
        }
        EmailOutcome outcome = EmailOutcome.of(sender.send(email.to(to), idempotencyKey));
        if (outcome instanceof EmailOutcome.Sent) {
            // only a delivered message uses the cap, so provider failures and their retries don't
            limiter.consume(perHour, subject);
            limiter.consume(perDay, subject);
            meters.counter("kybr.email.sent", "tipo", template).increment();
        }
        return outcome;
    }
}
