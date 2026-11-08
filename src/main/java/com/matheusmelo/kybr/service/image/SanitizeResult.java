package com.matheusmelo.kybr.service.image;

/**
 * A photo sanitizer's answer. Only {@link Sanitized} lets a photo through. {@link Refused} is the
 * photo's fault (it couldn't be decoded: 415); {@link Unavailable} is the sanitizer's (anything else,
 * fail closed: 503).
 */
public sealed interface SanitizeResult {

    /** The {@code resultado} tag of {@code kybr.image.reencode}. */
    String resultado();

    /** @param bytes the re-encoded photo; @param contentType its type, the same as the original's */
    record Sanitized(byte[] bytes, String contentType) implements SanitizeResult {
        @Override
        public String resultado() {
            return "ok";
        }
    }

    record Refused() implements SanitizeResult {
        @Override
        public String resultado() {
            return "rejected";
        }
    }

    /** @param timedOut whether the call ran out of time */
    record Unavailable(boolean timedOut) implements SanitizeResult {
        @Override
        public String resultado() {
            return timedOut ? "timeout" : "unavailable";
        }
    }
}
