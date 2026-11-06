package com.matheusmelo.kybr.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One fixed-window rate-limit counter. The {@code _id} holds only the policy,
 * the HMAC of the subject and the window start, never a raw subject. Mongo's TTL monitor removes
 * the document after {@code expiresAt}.
 */
@Document(RateLimitCounter.COLLECTION)
public record RateLimitCounter(@Id String id, long count, Instant expiresAt) {

    public static final String COLLECTION = "rate_limit_counters";
    public static final String COUNT = "count";
    public static final String EXPIRES_AT = "expiresAt";

    private static final char SEPARATOR = ':';

    /** {@code "{policy}:{subjectHmac}:{windowStartEpochSec}"}. */
    public static String idFor(String policy, String subjectHmac, long windowStartEpochSec) {
        requirePart("policy", policy);
        requirePart("subject hmac", subjectHmac);
        return policy + SEPARATOR + subjectHmac + SEPARATOR + windowStartEpochSec;
    }

    private static void requirePart(String name, String value) {
        if (value == null || value.isEmpty() || value.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException(name + " must be non-empty and contain no '" + SEPARATOR + "'");
        }
    }
}
