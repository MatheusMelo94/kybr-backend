package com.matheusmelo.kybr.config;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

/**
 * Typed {@code kybr.*} configuration, validated when bound, so a bad value stops startup.
 *
 * <p>The yml files for staging and prod give secrets no defaults, so a missing environment
 * variable binds as empty and fails here. Error messages name the property, never its value.
 */
@ConfigurationProperties("kybr")
public record KybrProperties(Bff bff, ClientIp clientIp, Hashing hashing, Idempotency idempotency, Limits limits,
        Logging logging) {

    /** Secrets (BFF key, hash secret) must decode to at least this many bytes (256 bits). */
    public static final int MIN_SECRET_BYTES = 32;

    public KybrProperties {
        bff = Objects.requireNonNullElseGet(bff, () -> new Bff(null, null, null));
        clientIp = Objects.requireNonNullElseGet(clientIp, () -> new ClientIp(null));
        hashing = Objects.requireNonNullElseGet(hashing, () -> new Hashing(null, null));
        idempotency = Objects.requireNonNullElseGet(idempotency, () -> new Idempotency(null));
        limits = Objects.requireNonNullElseGet(limits, () -> new Limits(null, null, null));
        logging = Objects.requireNonNullElseGet(logging, () -> new Logging(null));
    }

    /**
     * The server-to-server key the BFF sends in {@code X-Kybr-BFF-Key}.
     *
     * @param current required; base64url of at least 32 bytes
     * @param previous optional, set only during a rotation; the same rule, and never equal to current
     * @param exemptions the only (method, path pattern) pairs that don't need the key (deny by default)
     */
    public record Bff(String current, String previous, List<Route> exemptions) {
        public Bff {
            requireSecret("kybr.bff.current", current);
            previous = optionalSecret("kybr.bff.previous", previous);
            if (previous != null && previous.equals(current)) {
                throw new IllegalArgumentException("kybr.bff.previous must differ from kybr.bff.current");
            }
            exemptions = List.copyOf(Objects.requireNonNullElse(exemptions, List.of()));
        }
    }

    /**
     * Client-IP resolution.
     *
     * @param platformHeader the header the hosting edge sets with the socket IP ({@code Fly-Client-IP});
     *     null means use the socket address
     */
    public record ClientIp(String platformHeader) {
        public ClientIp {
            platformHeader = StringUtils.hasText(platformHeader) ? platformHeader : null;
        }
    }

    /**
     * The HMAC key for rate-limit subjects, idempotency keys and log-token hashing.
     *
     * @param secret required; base64url of at least 32 bytes
     * @param previous optional, kept for 24 h after a rotation (idempotency lookups only); never
     *     equal to secret
     */
    public record Hashing(String secret, String previous) {
        public Hashing {
            requireSecret("kybr.hashing.secret", secret);
            previous = optionalSecret("kybr.hashing.previous", previous);
            if (previous != null && previous.equals(secret)) {
                throw new IllegalArgumentException("kybr.hashing.previous must differ from kybr.hashing.secret");
            }
        }
    }

    /**
     * Replay-body encryption (idempotency replay bodies are stored encrypted).
     *
     * @param encKey required; a base64 Tink JSON keyset (its format is checked by the cipher)
     */
    public record Idempotency(String encKey) {
        public Idempotency {
            if (!StringUtils.hasText(encKey)) {
                throw new IllegalArgumentException("kybr.idempotency.enc-key is required");
            }
        }
    }

    /**
     * Request body limits.
     *
     * @param defaultMaxBody the limit for every route without an override
     * @param routes per-route overrides
     * @param multipart the only routes that take a body other than JSON: {@code multipart/form-data}
     *     only, each with its own cap (no route is ever uncapped)
     */
    public record Limits(DataSize defaultMaxBody, List<RouteLimit> routes, List<RouteLimit> multipart) {
        public Limits {
            defaultMaxBody = Objects.requireNonNullElse(defaultMaxBody, DataSize.ofMegabytes(1));
            routes = List.copyOf(Objects.requireNonNullElse(routes, List.of()));
            multipart = List.copyOf(Objects.requireNonNullElse(multipart, List.of()));
        }
    }

    /** A request method plus a path pattern, matched against the normalised request path. */
    public record Route(HttpMethod method, String pattern) {
        public Route {
            Objects.requireNonNull(method, "route method is required");
            Objects.requireNonNull(pattern, "route pattern is required");
        }
    }

    public record RouteLimit(HttpMethod method, String pattern, DataSize maxBody) {
        public RouteLimit {
            Objects.requireNonNull(method, "route method is required");
            Objects.requireNonNull(pattern, "route pattern is required");
            Objects.requireNonNull(maxBody, "route max-body is required");
        }
    }

    /**
     * Log redaction.
     *
     * @param tokenPrefixes path segments whose next segment is a capability token
     */
    public record Logging(List<String> tokenPrefixes) {
        public Logging {
            tokenPrefixes = List.copyOf(Objects.requireNonNullElse(tokenPrefixes, List.of()));
        }
    }

    private static void requireSecret(String property, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(property + " is required");
        }
        checkSecret(property, value);
    }

    private static String optionalSecret(String property, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        checkSecret(property, value);
        return value;
    }

    private static void checkSecret(String property, String value) {
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(property + " must be base64url");
        }
        if (decoded.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(property + " must decode to at least " + MIN_SECRET_BYTES + " bytes");
        }
    }
}
