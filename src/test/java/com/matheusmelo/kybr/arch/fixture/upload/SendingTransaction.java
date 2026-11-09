package com.matheusmelo.kybr.arch.fixture.upload;

import com.matheusmelo.kybr.service.TransactionRetry;
import com.matheusmelo.kybr.service.email.EmailMessage;
import com.matheusmelo.kybr.service.email.EmailSendResult;
import com.matheusmelo.kybr.service.email.EmailSender;

/** Violates the transaction rule: the retried callback reaches a send through a helper. */
public class SendingTransaction {

    private final EmailSender sender;

    public SendingTransaction(EmailSender sender) {
        this.sender = sender;
    }

    EmailSendResult commitAndNotify(EmailMessage message) {
        return TransactionRetry.DEFAULT.execute(() -> notifyNow(message));
    }

    private EmailSendResult notifyNow(EmailMessage message) {
        return sender.send(message, "fixture-key");
    }
}
