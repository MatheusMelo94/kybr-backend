package com.matheusmelo.kybr.service.email;

/**
 * How a send ended. {@link Retryable} is worth another attempt later (rate limited, a server error,
 * a timeout); {@link Permanent} never is (the provider refused the message or the recipient).
 * A {@code motivo} is a short fixed code such as {@code http_422}, never provider text, which can
 * echo the recipient.
 */
public sealed interface EmailSendResult {

    /** The {@code resultado} of the send, for metrics. */
    String resultado();

    record Sent() implements EmailSendResult {
        @Override
        public String resultado() {
            return "enviado";
        }
    }

    record Retryable(String motivo) implements EmailSendResult {
        @Override
        public String resultado() {
            return "temporario";
        }
    }

    record Permanent(String motivo) implements EmailSendResult {
        @Override
        public String resultado() {
            return "permanente";
        }
    }
}
