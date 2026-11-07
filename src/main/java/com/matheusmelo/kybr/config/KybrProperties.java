package com.matheusmelo.kybr.config;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
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
        Logging logging, Cnpja cnpja, Link link, Cache cache, Verification verification, RuntimeFlags runtimeFlags,
        Pii pii, Admin admin, Metrics metrics) {

    /** Secrets (BFF key, hash secret) must decode to at least this many bytes (256 bits). */
    public static final int MIN_SECRET_BYTES = 32;

    /** A serialised Tink keyset holding one 256-bit key is well over this many bytes. */
    static final int MIN_KEYSET_BYTES = 64;

    public KybrProperties {
        bff = Objects.requireNonNullElseGet(bff, () -> new Bff(null, null, null));
        clientIp = Objects.requireNonNullElseGet(clientIp, () -> new ClientIp(null, null));
        hashing = Objects.requireNonNullElseGet(hashing, () -> new Hashing(null, null));
        idempotency = Objects.requireNonNullElseGet(idempotency, () -> new Idempotency(null));
        limits = Objects.requireNonNullElseGet(limits, () -> new Limits(null, null, null));
        logging = Objects.requireNonNullElseGet(logging, () -> new Logging(null));
        cnpja = Objects.requireNonNullElseGet(cnpja, () -> new Cnpja(null, null, null, null, null, null, null, null,
                null, null, null));
        link = Objects.requireNonNullElseGet(link, () -> new Link(null, null, null, null));
        cache = Objects.requireNonNullElseGet(cache, () -> new Cache(null, null));
        verification = Objects.requireNonNullElseGet(verification,
                () -> new Verification(0, 0, 0, null, null, null, null));
        runtimeFlags = Objects.requireNonNullElseGet(runtimeFlags, () -> new RuntimeFlags(null));
        pii = Objects.requireNonNullElseGet(pii, () -> new Pii(null));
        admin = Objects.requireNonNullElseGet(admin, () -> new Admin(null, null, null, false));
        metrics = Objects.requireNonNullElseGet(metrics, () -> new Metrics(0));
        if (pii.encKey().equals(idempotency.encKey())) {
            throw new IllegalArgumentException("kybr.pii.enc-key must be a different keyset from kybr.idempotency.enc-key");
        }
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
     * @param notExpected the (method, path pattern) pairs where the BFF legitimately sends no client
     *     IP (cached or browser-direct routes); a missing client IP there is not alert-worthy
     */
    public record ClientIp(String platformHeader, List<Route> notExpected) {
        public ClientIp {
            platformHeader = StringUtils.hasText(platformHeader) ? platformHeader : null;
            notExpected = List.copyOf(Objects.requireNonNullElse(notExpected, List.of()));
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

    /**
     * The company-data provider.
     *
     * @param mode {@code stub} (synthetic fixtures) or {@code live}
     * @param apiKey required when live; sent in a request header only
     * @param baseUrl the provider's API base URL ({@code https:})
     * @param timeouts connect and read timeouts
     * @param maxAgeDays the oldest provider-side cached record accepted
     * @param strategy the provider-side cache strategy sent with each call
     * @param creditCosts credits charged per call type
     * @param ratePerMinute the plan's per-minute call limit, enforced across machines
     * @param monthlyCredits the plan's monthly credits (no prod default); every budget derives from it
     * @param authHeader the request header that carries the API key (a header name, never a value)
     * @param addons what a full check asks the provider for, beyond the base record
     */
    public record Cnpja(Mode mode, String apiKey, URI baseUrl, Timeouts timeouts, Integer maxAgeDays, String strategy,
            CreditCosts creditCosts, Integer ratePerMinute, Long monthlyCredits, String authHeader, Addons addons) {

        public enum Mode { STUB, LIVE }

        /**
         * The add-ons a verification's full check asks for.
         *
         * @param simples the Simples/MEI regime add-on
         * @param registrations the states whose registrations are fetched: {@code BR} (every state) or a
         *     comma-separated list of state codes
         */
        public record Addons(boolean simples, String registrations) {
            private static final Pattern STATES =
                    Pattern.compile("BR|[A-Z]{2}(?:,[A-Z]{2})*");

            public Addons {
                if (registrations == null || !STATES.matcher(registrations).matches()) {
                    throw new IllegalArgumentException(
                            "kybr.cnpja.addons.registrations must be BR or a comma-separated list of state codes");
                }
            }
        }

        /** An HTTP header name: RFC 9110 token characters only. */
        private static final Pattern HEADER_NAME =
                Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

        public Cnpja {
            mode = Objects.requireNonNullElse(mode, Mode.STUB);
            apiKey = StringUtils.hasText(apiKey) ? apiKey : null;
            if (mode == Mode.LIVE && apiKey == null) {
                throw new IllegalArgumentException("kybr.cnpja.api-key is required when kybr.cnpja.mode is live");
            }
            if (mode == Mode.LIVE && monthlyCredits == null) {
                throw new IllegalArgumentException("kybr.cnpja.monthly-credits is required when kybr.cnpja.mode is live");
            }
            requireHttps("kybr.cnpja.base-url", baseUrl);
            timeouts = Objects.requireNonNullElseGet(timeouts, () -> new Timeouts(null, null, null));
            requirePositive("kybr.cnpja.max-age-days", maxAgeDays);
            if (!StringUtils.hasText(strategy)) {
                throw new IllegalArgumentException("kybr.cnpja.strategy is required");
            }
            creditCosts = Objects.requireNonNullElseGet(creditCosts, () -> new CreditCosts(0, 0, 0));
            requirePositive("kybr.cnpja.rate-per-minute", ratePerMinute);
            if (monthlyCredits != null && monthlyCredits < 1) {
                throw new IllegalArgumentException("kybr.cnpja.monthly-credits must be positive");
            }
            if (authHeader == null || !HEADER_NAME.matcher(authHeader).matches()) {
                throw new IllegalArgumentException("kybr.cnpja.auth-header must be an HTTP header name");
            }
            if (addons == null) {
                throw new IllegalArgumentException("kybr.cnpja.addons is required");
            }
        }
    }

    public record Timeouts(Duration connect, Duration lookupRead, Duration workerRead) {
        public Timeouts {
            requirePositive("kybr.cnpja.timeouts.connect", connect);
            requirePositive("kybr.cnpja.timeouts.lookup-read", lookupRead);
            requirePositive("kybr.cnpja.timeouts.worker-read", workerRead);
        }
    }

    /** Credits per call: the base record, the Simples/MEI add-on and the state registration add-on. */
    public record CreditCosts(int base, int simples, int ie) {
        public CreditCosts {
            if (base < 1 || simples < 0 || ie < 0) {
                throw new IllegalArgumentException("kybr.cnpja.credit-costs must be set (base >= 1, add-ons >= 0)");
            }
        }
    }

    /**
     * The seller link.
     *
     * @param limits per-IP, per-case and per-CNPJ request limits
     * @param budgets provider credit budgets; each one degrades service, none fails a request
     * @param reviewDeadline how long a pending case waits for a decision
     * @param regularizacao where a seller with an irregular registration is sent
     */
    public record Link(LinkLimits limits, Budgets budgets, Duration reviewDeadline, Regularizacao regularizacao) {
        public Link {
            limits = Objects.requireNonNullElseGet(limits,
                    () -> new LinkLimits(null, null, null, null, null, null, null, null, null, null, null));
            budgets = Objects.requireNonNullElseGet(budgets, () -> new Budgets(0, 0, 0, 0, 0, 0));
            requirePositive("kybr.link.review-deadline", reviewDeadline);
            regularizacao = Objects.requireNonNullElseGet(regularizacao, () -> new Regularizacao(null));
        }
    }

    /**
     * The seller link's request limits. The {@code -unknown} variants apply per slug when the
     * client IP is unknown (a shared subject), instead of per IP.
     */
    public record LinkLimits(LimitWindow lookupIp, LimitWindow lookupIpDaily, LimitWindow lookupIpUnknown,
            LimitWindow verifyIp, LimitWindow verifyIpUnknown, LimitWindow verifyCnpj, LimitWindow caseIp,
            LimitWindow caseIpUnknown, LimitWindow contactIp, LimitWindow contactIpUnknown, LimitWindow contactCase) {
        public LinkLimits {
            requireWindow("lookup-ip", lookupIp);
            requireWindow("lookup-ip-daily", lookupIpDaily);
            requireWindow("lookup-ip-unknown", lookupIpUnknown);
            requireWindow("verify-ip", verifyIp);
            requireWindow("verify-ip-unknown", verifyIpUnknown);
            requireWindow("verify-cnpj", verifyCnpj);
            requireWindow("case-ip", caseIp);
            requireWindow("case-ip-unknown", caseIpUnknown);
            requireWindow("contact-ip", contactIp);
            requireWindow("contact-ip-unknown", contactIpUnknown);
            requireWindow("contact-case", contactCase);
        }

        private static void requireWindow(String name, LimitWindow window) {
            if (window == null) {
                throw new IllegalArgumentException("kybr.link.limits." + name + " is required");
            }
        }
    }

    /** At most {@code limit} calls per fixed {@code window} (a whole number of seconds). */
    public record LimitWindow(int limit, Duration window) {
        public LimitWindow {
            if (limit < 1 || window == null || window.toSeconds() < 1 || window.toNanosPart() != 0) {
                throw new IllegalArgumentException("a kybr.link.limits entry needs limit >= 1 and a whole-second window");
            }
        }
    }

    /**
     * Provider credit budgets.
     *
     * @param d2Share the share of monthly credits company lookups may spend, in (0, 1); the rest is
     *     kept for verifications
     * @param burst how many times the even daily rate a busy day may spend (&gt; 0)
     * @param lookupPerSlugDaily fresh lookups per link per day before lookups go cache-only
     * @param verifyPerSlugDaily fresh full checks per link per day before verifications defer
     * @param noDataPerSlugDaily "no data found" outcomes per link per day before verifications defer
     * @param cacheReadsPerSlugAlert cache reads per link per day that raise an alert (never a block)
     */
    public record Budgets(double d2Share, double burst, int lookupPerSlugDaily, int verifyPerSlugDaily,
            int noDataPerSlugDaily, int cacheReadsPerSlugAlert) {
        public Budgets {
            if (!(d2Share > 0 && d2Share < 1)) {
                throw new IllegalArgumentException("kybr.link.budgets.d2-share must be between 0 and 1, exclusive");
            }
            if (!(burst > 0)) {
                throw new IllegalArgumentException("kybr.link.budgets.burst must be positive");
            }
            requirePositive("kybr.link.budgets.lookup-per-slug-daily", lookupPerSlugDaily);
            requirePositive("kybr.link.budgets.verify-per-slug-daily", verifyPerSlugDaily);
            requirePositive("kybr.link.budgets.no-data-per-slug-daily", noDataPerSlugDaily);
            requirePositive("kybr.link.budgets.cache-reads-per-slug-alert", cacheReadsPerSlugAlert);
        }
    }

    /** @param receitaUrl the federal revenue regularisation page: {@code https:} on gov.br or a subdomain */
    public record Regularizacao(URI receitaUrl) {
        public Regularizacao {
            if (!isGovBr(receitaUrl)) {
                throw new IllegalArgumentException(
                        "kybr.link.regularizacao.receita-url must be an https: URL on gov.br or a gov.br subdomain");
            }
        }
    }

    /** Company lookup cache lifetimes: a found record, and a "not found" answer. */
    public record Cache(Duration positiveTtl, Duration negativeTtl) {
        public Cache {
            requirePositive("kybr.cache.positive-ttl", positiveTtl);
            requirePositive("kybr.cache.negative-ttl", negativeTtl);
        }
    }

    /**
     * Asynchronous verification processing.
     *
     * @param workers concurrent verifications per machine
     * @param queue verifications waiting per machine before submission fails over to the poller
     * @param maxAttempts provider attempts before a case goes to manual review
     * @param backoff the wait before each retry, in order (the last repeats)
     * @param lease how long a claim holds a case before another machine may take it over
     * @param pollInterval how often each machine looks for unclaimed or stale work
     * @param pollerEnabled whether this machine runs the recovery poller (default true); off only in
     *     tests, so background claims don't race them; prod refuses to start with it off
     */
    public record Verification(int workers, int queue, int maxAttempts, List<Duration> backoff, Duration lease,
            Duration pollInterval, Boolean pollerEnabled) {
        public Verification {
            pollerEnabled = Objects.requireNonNullElse(pollerEnabled, Boolean.TRUE);
            requirePositive("kybr.verification.workers", workers);
            requirePositive("kybr.verification.queue", queue);
            requirePositive("kybr.verification.max-attempts", maxAttempts);
            backoff = List.copyOf(Objects.requireNonNullElse(backoff, List.of()));
            if (backoff.isEmpty() || backoff.stream().anyMatch(d -> d.isNegative() || d.isZero())) {
                throw new IllegalArgumentException("kybr.verification.backoff needs at least one positive duration");
            }
            requirePositive("kybr.verification.lease", lease);
            requirePositive("kybr.verification.poll-interval", pollInterval);
        }
    }

    /** @param cacheTtl how long a machine may serve a kill-switch flag before re-reading it */
    public record RuntimeFlags(Duration cacheTtl) {
        public RuntimeFlags {
            requirePositive("kybr.runtime-flags.cache-ttl", cacheTtl);
        }
    }

    /**
     * Encryption of personal data fields (a seller's contact e-mail).
     *
     * @param encKey required; a base64 Tink JSON keyset, distinct from the replay-body keyset (its
     *     format is checked by the cipher)
     */
    public record Pii(String encKey) {
        public Pii {
            if (!StringUtils.hasText(encKey)) {
                throw new IllegalArgumentException("kybr.pii.enc-key is required");
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(encKey);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("kybr.pii.enc-key must be base64");
            }
            if (decoded.length < MIN_KEYSET_BYTES) {
                throw new IllegalArgumentException("kybr.pii.enc-key is too short to be a keyset");
            }
        }
    }

    /**
     * The operator surface on the management port.
     *
     * @param token required; base64url of at least 32 bytes, sent as a bearer token
     * @param tokenPrevious optional, set only during a rotation; never equal to token
     * @param maxBody the largest admin request body
     * @param allowExplicitSlug whether provisioning may take a chosen slug; never in prod
     */
    public record Admin(String token, String tokenPrevious, DataSize maxBody, boolean allowExplicitSlug) {
        public Admin {
            requireSecret("kybr.admin.token", token);
            tokenPrevious = optionalSecret("kybr.admin.token-previous", tokenPrevious);
            if (tokenPrevious != null && tokenPrevious.equals(token)) {
                throw new IllegalArgumentException("kybr.admin.token-previous must differ from kybr.admin.token");
            }
            if (maxBody == null || maxBody.toBytes() < 1) {
                throw new IllegalArgumentException("kybr.admin.max-body is required");
            }
        }
    }

    /** @param maxSlugTags distinct {@code slug} metric tag values before overflow */
    public record Metrics(int maxSlugTags) {
        public Metrics {
            requirePositive("kybr.metrics.max-slug-tags", maxSlugTags);
        }
    }

    private static boolean isGovBr(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("gov.br") || host.endsWith(".gov.br");
    }

    private static void requireHttps(String property, URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(property + " must be an https: URL");
        }
    }

    private static void requirePositive(String property, Integer value) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }

    private static void requirePositive(String property, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " must be a positive duration");
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
