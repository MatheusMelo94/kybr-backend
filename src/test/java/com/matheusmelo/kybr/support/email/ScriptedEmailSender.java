package com.matheusmelo.kybr.support.email;

import com.matheusmelo.kybr.service.email.EmailMessage;
import com.matheusmelo.kybr.service.email.EmailSendResult;
import com.matheusmelo.kybr.service.email.EmailSender;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A sender a test can script: the nth send fails retryably or permanently, or every send fails
 * retryably. Every call is captured, failures included. Unscripted, every send succeeds. Calls count
 * from 1 after construction or {@link #reset()}. Made the primary sender by
 * {@link EmailTestDoublesConfiguration}.
 */
public class ScriptedEmailSender implements EmailSender {

    public record Call(EmailMessage message, String idempotencyKey) {
    }

    private final List<Call> calls = new ArrayList<>();
    private final Set<Integer> retryable = ConcurrentHashMap.newKeySet();
    private final Set<Integer> permanent = ConcurrentHashMap.newKeySet();
    private volatile boolean alwaysRetryable;

    @Override
    public EmailSendResult send(EmailMessage message, String idempotencyKey) {
        int call;
        synchronized (calls) {
            calls.add(new Call(message, idempotencyKey));
            call = calls.size();
        }
        if (permanent.contains(call)) {
            return new EmailSendResult.Permanent("scripted");
        }
        if (alwaysRetryable || retryable.contains(call)) {
            return new EmailSendResult.Retryable("scripted");
        }
        return new EmailSendResult.Sent();
    }

    /** The {@code call}th send (from 1) fails, worth a retry. */
    public void retryableOnCall(int call) {
        retryable.add(call);
    }

    /** The {@code call}th send (from 1) fails for good. */
    public void permanentOnCall(int call) {
        permanent.add(call);
    }

    /** Every send fails, worth a retry, until {@link #reset()}. */
    public void alwaysRetryable() {
        alwaysRetryable = true;
    }

    /** Every send so far, in order. */
    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    public void reset() {
        synchronized (calls) {
            calls.clear();
        }
        retryable.clear();
        permanent.clear();
        alwaysRetryable = false;
    }
}
