package com.matheusmelo.kybr.support.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.service.email.EmailMessage;
import com.matheusmelo.kybr.service.email.EmailSendResult;
import org.junit.jupiter.api.Test;

class ScriptedEmailSenderTest {

    private final ScriptedEmailSender sender = new ScriptedEmailSender();

    @Test
    void unscriptedEverySendSucceedsAndIsCaptured() {
        EmailMessage message = message("a");

        assertThat(sender.send(message, "k1")).isEqualTo(new EmailSendResult.Sent());
        assertThat(sender.calls()).containsExactly(new ScriptedEmailSender.Call(message, "k1"));
    }

    @Test
    void failsTheScriptedSendsRetryablyOrPermanentlyAndCapturesThemToo() {
        sender.retryableOnCall(2);
        sender.permanentOnCall(3);

        assertThat(sender.send(message("1"), "k1")).isInstanceOf(EmailSendResult.Sent.class);
        assertThat(sender.send(message("2"), "k2")).isInstanceOf(EmailSendResult.Retryable.class);
        assertThat(sender.send(message("3"), "k3")).isInstanceOf(EmailSendResult.Permanent.class);
        assertThat(sender.send(message("4"), "k4")).isInstanceOf(EmailSendResult.Sent.class);
        assertThat(sender.calls()).extracting(ScriptedEmailSender.Call::idempotencyKey)
                .containsExactly("k1", "k2", "k3", "k4");
    }

    @Test
    void canFailEverySendRetryably() {
        sender.alwaysRetryable();

        assertThat(sender.send(message("1"), "k1")).isInstanceOf(EmailSendResult.Retryable.class);
        assertThat(sender.send(message("2"), "k2")).isInstanceOf(EmailSendResult.Retryable.class);
    }

    @Test
    void resetForgetsScriptsAndCalls() {
        sender.permanentOnCall(1);
        sender.reset();

        assertThat(sender.send(message("1"), "k1")).isInstanceOf(EmailSendResult.Sent.class);
        sender.reset();
        assertThat(sender.calls()).isEmpty();
    }

    private static EmailMessage message(String subject) {
        return new EmailMessage("vendedor@example.com", subject, "texto", "<p>texto</p>");
    }
}
