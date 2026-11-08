package com.matheusmelo.kybr.service.email;

/**
 * How one e-mail through {@link EmailService} ended: handed to the provider, dropped by the
 * per-address cap (never queued, never retried), or a provider failure worth a retry or not.
 */
public sealed interface EmailOutcome {

    record Sent() implements EmailOutcome {
    }

    record Capped() implements EmailOutcome {
    }

    /** @param motivo a short fixed code, never provider text */
    record Retryable(String motivo) implements EmailOutcome {
    }

    /** @param motivo a short fixed code, never provider text */
    record Permanent(String motivo) implements EmailOutcome {
    }

    static EmailOutcome of(EmailSendResult result) {
        return switch (result) {
            case EmailSendResult.Sent sent -> new Sent();
            case EmailSendResult.Retryable retryable -> new Retryable(retryable.motivo());
            case EmailSendResult.Permanent permanent -> new Permanent(permanent.motivo());
        };
    }
}
