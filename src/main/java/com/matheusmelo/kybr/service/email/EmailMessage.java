package com.matheusmelo.kybr.service.email;

/**
 * One e-mail, rendered: both parts carry the same words. The sender is configuration, not part of
 * the message.
 *
 * @param to one recipient address; personal data, never logged
 */
public record EmailMessage(String to, String subject, String text, String html) {

    /** Never prints the recipient or the content. */
    @Override
    public String toString() {
        return "EmailMessage[subject=" + subject + "]";
    }
}
