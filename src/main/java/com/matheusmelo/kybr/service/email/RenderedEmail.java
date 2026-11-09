package com.matheusmelo.kybr.service.email;

/**
 * A rendered e-mail: the subject, the plain-text part and the HTML part generated from it, so both
 * parts carry the same words.
 */
public record RenderedEmail(String subject, String text, String html) {

    /** The message to one recipient. */
    public EmailMessage to(String address) {
        return new EmailMessage(address, subject, text, html);
    }

    /** Never prints the content. */
    @Override
    public String toString() {
        return "RenderedEmail[subject=" + subject + "]";
    }
}
