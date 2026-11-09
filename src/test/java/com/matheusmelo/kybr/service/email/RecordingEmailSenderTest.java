package com.matheusmelo.kybr.service.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecordingEmailSenderTest {

    private final RecordingEmailSender sender = new RecordingEmailSender();

    @Test
    void recordsTheMessageAndItsIdempotencyKeyAndNeverSends() {
        EmailMessage message = new EmailMessage("vendedor@example.com", "Assunto", "Texto", "<p>Texto</p>");

        EmailSendResult result = sender.send(message, "event-1");

        assertThat(result).isEqualTo(new EmailSendResult.Sent());
        assertThat(sender.recorded()).containsExactly(new RecordingEmailSender.Recorded(message, "event-1"));
    }

    @Test
    void keepsOnlyTheMostRecentMessages() {
        for (int i = 0; i < RecordingEmailSender.KEPT + 5; i++) {
            sender.send(new EmailMessage("a@example.com", "s" + i, "t", "<p>t</p>"), "k" + i);
        }

        assertThat(sender.recorded()).hasSize(RecordingEmailSender.KEPT);
        assertThat(sender.recorded().getLast().idempotencyKey()).isEqualTo("k" + (RecordingEmailSender.KEPT + 4));
        sender.clear();
        assertThat(sender.recorded()).isEmpty();
    }

    @Test
    void aMessagesTextFormNeverShowsTheRecipient() {
        assertThat(new EmailMessage("vendedor@example.com", "s", "t", "h").toString())
                .doesNotContain("vendedor@example.com");
    }

    @Test
    void resultsNameTheirMetricResult() {
        assertThat(new EmailSendResult.Sent().resultado()).isEqualTo("enviado");
        assertThat(new EmailSendResult.Retryable("http_503").resultado()).isEqualTo("temporario");
        assertThat(new EmailSendResult.Permanent("http_422").resultado()).isEqualTo("permanente");
    }
}
