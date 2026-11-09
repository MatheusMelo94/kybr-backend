package com.matheusmelo.kybr.service.email;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The sender stand-in ({@code kybr.email.mode=recording}: local, docker, test and staging by
 * default). It keeps the most recent messages in memory and sends nothing. Never in prod (the bean
 * doesn't exist there, and the prod guard refuses the mode).
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "kybr.email", name = "mode", havingValue = "recording", matchIfMissing = true)
public class RecordingEmailSender implements EmailSender {

    /** How many messages are kept; older ones are dropped, so a long-running staging machine stays small. */
    public static final int KEPT = 100;

    public record Recorded(EmailMessage message, String idempotencyKey) {
    }

    private final Deque<Recorded> recorded = new ArrayDeque<>();

    @Override
    public synchronized EmailSendResult send(EmailMessage message, String idempotencyKey) {
        if (recorded.size() == KEPT) {
            recorded.removeFirst();
        }
        recorded.addLast(new Recorded(message, idempotencyKey));
        return new EmailSendResult.Sent();
    }

    /** The kept messages, oldest first. */
    public synchronized List<Recorded> recorded() {
        return List.copyOf(recorded);
    }

    public synchronized void clear() {
        recorded.clear();
    }
}
