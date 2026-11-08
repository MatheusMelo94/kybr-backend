package com.matheusmelo.kybr.service.email;

/**
 * Hands one e-mail to the provider. Never throws for a provider problem, and never called inside a
 * business transaction: a send can't be rolled back.
 */
public interface EmailSender {

    /**
     * @param idempotencyKey the provider-side deduplication key (an outbox event's id), so a retried
     *     send is delivered at most once
     */
    EmailSendResult send(EmailMessage message, String idempotencyKey);
}
