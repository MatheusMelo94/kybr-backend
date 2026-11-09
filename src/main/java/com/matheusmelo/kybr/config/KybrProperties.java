package com.matheusmelo.kybr.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Period;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
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
        Pii pii, Admin admin, Metrics metrics, Upload upload, Image image, Sanitizer sanitizer, Scanner scanner,
        Storage storage, Email email, Retention retention, Cors cors, Scan scan) {

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
        upload = Objects.requireNonNullElseGet(upload, () -> new Upload(null, null, null, null, null, null));
        image = Objects.requireNonNullElseGet(image, () -> new Image(null, null, null, null));
        sanitizer = Objects.requireNonNullElseGet(sanitizer, () -> new Sanitizer(null, null, null, null));
        scanner = Objects.requireNonNullElseGet(scanner,
                () -> new Scanner(null, null, null, null, null, null, null, null, null, null));
        storage = Objects.requireNonNullElseGet(storage,
                () -> new Storage(null, null, null, null, null, null, null, null, null, null, null, null, null));
        email = Objects.requireNonNullElseGet(email,
                () -> new Email(null, null, null, null, false, null, null, null, null, null, null));
        retention = Objects.requireNonNullElseGet(retention,
                () -> new Retention(null, null, null, null, null, null, null));
        cors = Objects.requireNonNullElseGet(cors, () -> new Cors(null));
        scan = Objects.requireNonNullElseGet(scan, () -> new Scan(null, null, null, null, null, null, null, null, null));
        if (pii.encKey().equals(idempotency.encKey())) {
            throw new IllegalArgumentException("kybr.pii.enc-key must be a different keyset from kybr.idempotency.enc-key");
        }
        if (image.mode() == Image.Mode.REMOTE) {
            requireRemoteSanitizer(sanitizer, scanner);
        }
    }

    /** The remote sanitizer is reached over the scan link: its address and the link's TLS material are required. */
    private static void requireRemoteSanitizer(Sanitizer sanitizer, Scanner scanner) {
        if (sanitizer.host() == null) {
            throw new IllegalArgumentException("kybr.sanitizer.host is required when kybr.image.mode is remote");
        }
        if (sanitizer.port() == null) {
            throw new IllegalArgumentException("kybr.sanitizer.port is required when kybr.image.mode is remote");
        }
        requireSha256("kybr.scanner.spki-sha256", scanner.spkiSha256());
        requirePem("kybr.scanner.client-cert", scanner.clientCert(), "CERTIFICATE");
        requirePem("kybr.scanner.client-key", scanner.clientKey(), "PRIVATE KEY");
        requirePem("kybr.scanner.ca", scanner.ca(), "CERTIFICATE");
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

    /**
     * Seller document uploads.
     *
     * @param maxBytesPerTipo original file bytes accepted per document type, across calls
     * @param maxFilesPerCase files per case, across document types
     * @param concurrency uploads per machine in the processing steps at once
     * @param reencodeConcurrency photo re-encodes per machine at once
     * @param tempDir where multipart parts are written (always to disk): an absolute path to a
     *     dedicated, size-bounded directory, wiped at startup
     * @param limits request limits
     */
    public record Upload(DataSize maxBytesPerTipo, Integer maxFilesPerCase, Integer concurrency,
            Integer reencodeConcurrency, Path tempDir, UploadLimits limits) {
        public Upload {
            if (maxBytesPerTipo == null || maxBytesPerTipo.toBytes() < 1) {
                throw new IllegalArgumentException("kybr.upload.max-bytes-per-tipo must be positive");
            }
            requirePositive("kybr.upload.max-files-per-case", maxFilesPerCase);
            requirePositive("kybr.upload.concurrency", concurrency);
            requirePositive("kybr.upload.reencode-concurrency", reencodeConcurrency);
            if (tempDir == null || !tempDir.isAbsolute()) {
                throw new IllegalArgumentException("kybr.upload.temp-dir must be an absolute path");
            }
            tempDir = tempDir.normalize();
            if (tempDir.getParent() == null || isShared(tempDir)) {
                // wiped at startup: never a root, the system temp directory or a home directory
                throw new IllegalArgumentException("kybr.upload.temp-dir must be a dedicated directory");
            }
            limits = Objects.requireNonNullElseGet(limits, () -> new UploadLimits(null, null, null));
        }

        private static boolean isShared(Path dir) {
            return java.util.stream.Stream.of("java.io.tmpdir", "user.home", "user.dir")
                    .map(System::getProperty).filter(Objects::nonNull)
                    .anyMatch(shared -> Path.of(shared).toAbsolutePath().normalize().equals(dir));
        }
    }

    /**
     * Document upload request limits.
     *
     * @param ip per socket IP
     * @param ipUnknown per link when the client IP is unknown (a shared subject)
     * @param perCase per case ({@code kybr.upload.limits.case})
     */
    public record UploadLimits(LimitWindow ip, LimitWindow ipUnknown, @Name("case") LimitWindow perCase) {
        public UploadLimits {
            requireLimit("kybr.upload.limits.ip", ip);
            requireLimit("kybr.upload.limits.ip-unknown", ipUnknown);
            requireLimit("kybr.upload.limits.case", perCase);
        }
    }

    /**
     * Photo re-encoding. Photos are decoded only by the isolated sanitizer service; the API reads
     * image headers and never pixels.
     *
     * @param mode {@code remote} (the sanitizer service; required in prod) or {@code in-process-test}
     *     (non-production, synthetic images only)
     * @param maxPixels the largest width × height accepted, read from the header before any decode
     * @param maxSide the largest width or height accepted
     * @param targetLongEdge the long edge of a re-encoded photo, at most {@code maxSide}
     */
    public record Image(Mode mode, Long maxPixels, Integer maxSide, Integer targetLongEdge) {

        public enum Mode { REMOTE, IN_PROCESS_TEST }

        public Image {
            mode = Objects.requireNonNullElse(mode, Mode.REMOTE);
            if (maxPixels == null || maxPixels < 1) {
                throw new IllegalArgumentException("kybr.image.max-pixels must be positive");
            }
            requirePositive("kybr.image.max-side", maxSide);
            requirePositive("kybr.image.target-long-edge", targetLongEdge);
            if (targetLongEdge > maxSide) {
                throw new IllegalArgumentException("kybr.image.target-long-edge must not exceed kybr.image.max-side");
            }
        }
    }

    /**
     * The photo sanitizer service in the isolated scanner organisation, reached over the scanner's
     * mutual TLS (same client certificate, CA and pin).
     *
     * @param host its host name; required in remote mode
     * @param port its TLS port; required in remote mode
     * @param timeout the whole call, upload to last byte
     * @param maxResponse the largest re-encoded photo accepted back
     */
    public record Sanitizer(String host, Integer port, Duration timeout, DataSize maxResponse) {
        public Sanitizer {
            host = StringUtils.hasText(host) ? host : null;
            if (port != null) {
                requirePort("kybr.sanitizer.port", port);
            }
            requirePositive("kybr.sanitizer.timeout", timeout);
            if (maxResponse == null || maxResponse.toBytes() < 1) {
                throw new IllegalArgumentException("kybr.sanitizer.max-response must be positive");
            }
        }
    }

    /**
     * Malware scanning.
     *
     * @param mode {@code stub} (test signatures only; never in prod) or {@code clamd} (the isolated
     *     scanner over mutual TLS)
     * @param host the scanner's host name, checked against its certificate; required for clamd
     * @param port the scanner's TLS port
     * @param connectTimeout connection timeout
     * @param readTimeout the wait for a verdict
     * @param maxSignatureAge a scan session whose scanner has signatures older than this scans nothing (its files wait)
     * @param spkiSha256 base64 SHA-256 of the scanner certificate's public key (the pin); required for clamd
     * @param clientCert this API's client certificate, PEM; required for clamd
     * @param clientKey its private key, PEM (PKCS#8); required for clamd; a secret
     * @param ca the certificate authority for the scanner link only, PEM; required for clamd
     */
    public record Scanner(Mode mode, String host, Integer port, Duration connectTimeout, Duration readTimeout,
            Duration maxSignatureAge, String spkiSha256, String clientCert,
            String clientKey, String ca) {

        public enum Mode { STUB, CLAMD }

        public Scanner {
            mode = Objects.requireNonNullElse(mode, Mode.STUB);
            host = StringUtils.hasText(host) ? host : null;
            spkiSha256 = StringUtils.hasText(spkiSha256) ? spkiSha256 : null;
            clientCert = StringUtils.hasText(clientCert) ? clientCert : null;
            clientKey = StringUtils.hasText(clientKey) ? clientKey : null;
            ca = StringUtils.hasText(ca) ? ca : null;
            requirePort("kybr.scanner.port", port);
            requirePositive("kybr.scanner.connect-timeout", connectTimeout);
            requirePositive("kybr.scanner.read-timeout", readTimeout);
            requirePositive("kybr.scanner.max-signature-age", maxSignatureAge);
            if (mode == Mode.CLAMD) {
                if (host == null) {
                    throw new IllegalArgumentException("kybr.scanner.host is required when kybr.scanner.mode is clamd");
                }
                requireSha256("kybr.scanner.spki-sha256", spkiSha256);
                requirePem("kybr.scanner.client-cert", clientCert, "CERTIFICATE");
                requirePem("kybr.scanner.client-key", clientKey, "PRIVATE KEY");
                requirePem("kybr.scanner.ca", ca, "CERTIFICATE");
            }
        }

        /** Never prints the key or the certificates. */
        @Override
        public String toString() {
            return "Scanner[mode=" + mode + ", host=" + host + ", port=" + port + "]";
        }
    }

    /**
     * Document storage.
     *
     * @param mode {@code memory} (never in prod) or {@code s3}
     * @param bucket the private bucket; required for s3
     * @param region the bucket's region
     * @param kmsKeyId the customer-managed key every object is encrypted under; required for s3
     * @param quarantineBucket the private bucket received files wait in until scanned; required for s3
     * @param quarantineKmsKeyId the quarantine's own customer-managed key; required for s3
     * @param roleArn the role assumed with the platform's identity token; required for s3 unless
     *     static keys are set
     * @param oidcAudience the audience the identity token is requested for
     * @param accessKeyId the static-key fallback (both halves or neither); a secret
     * @param secretAccessKey the static-key fallback's secret; a secret
     * @param endpoint an S3-compatible endpoint override (tests only); null for the real service
     * @param connectTimeout connection timeout
     * @param callTimeout the whole call, retries included
     */
    public record Storage(Mode mode, String bucket, String region, String kmsKeyId, String quarantineBucket,
            String quarantineKmsKeyId, String roleArn,
            String oidcAudience, String accessKeyId, String secretAccessKey, URI endpoint, Duration connectTimeout,
            Duration callTimeout) {

        public enum Mode { MEMORY, S3 }

        public Storage {
            mode = Objects.requireNonNullElse(mode, Mode.MEMORY);
            bucket = StringUtils.hasText(bucket) ? bucket : null;
            kmsKeyId = StringUtils.hasText(kmsKeyId) ? kmsKeyId : null;
            quarantineBucket = StringUtils.hasText(quarantineBucket) ? quarantineBucket : null;
            quarantineKmsKeyId = StringUtils.hasText(quarantineKmsKeyId) ? quarantineKmsKeyId : null;
            roleArn = StringUtils.hasText(roleArn) ? roleArn : null;
            accessKeyId = StringUtils.hasText(accessKeyId) ? accessKeyId : null;
            secretAccessKey = StringUtils.hasText(secretAccessKey) ? secretAccessKey : null;
            if (!StringUtils.hasText(region)) {
                throw new IllegalArgumentException("kybr.storage.region is required");
            }
            if (!StringUtils.hasText(oidcAudience)) {
                throw new IllegalArgumentException("kybr.storage.oidc-audience is required");
            }
            requirePositive("kybr.storage.connect-timeout", connectTimeout);
            requirePositive("kybr.storage.call-timeout", callTimeout);
            if ((accessKeyId == null) != (secretAccessKey == null)) {
                throw new IllegalArgumentException(
                        "kybr.storage.access-key-id and kybr.storage.secret-access-key must be set together");
            }
            if (mode == Mode.S3) {
                if (bucket == null) {
                    throw new IllegalArgumentException("kybr.storage.bucket is required when kybr.storage.mode is s3");
                }
                if (kmsKeyId == null) {
                    throw new IllegalArgumentException(
                            "kybr.storage.kms-key-id is required when kybr.storage.mode is s3");
                }
                if (quarantineBucket == null) {
                    throw new IllegalArgumentException(
                            "kybr.storage.quarantine-bucket is required when kybr.storage.mode is s3");
                }
                if (quarantineKmsKeyId == null) {
                    throw new IllegalArgumentException(
                            "kybr.storage.quarantine-kms-key-id is required when kybr.storage.mode is s3");
                }
                if (quarantineBucket.equals(bucket) || quarantineKmsKeyId.equals(kmsKeyId)) {
                    throw new IllegalArgumentException("kybr.storage.quarantine-bucket and "
                            + "kybr.storage.quarantine-kms-key-id must differ from the final bucket and key");
                }
                if (roleArn == null && accessKeyId == null) {
                    throw new IllegalArgumentException(
                            "kybr.storage.role-arn (or the static keys) is required when kybr.storage.mode is s3");
                }
            }
        }

        /** Never prints the static keys. */
        @Override
        public String toString() {
            return "Storage[mode=" + mode + ", bucket=" + bucket + ", quarantineBucket=" + quarantineBucket
                    + ", region=" + region + ", staticKeys="
                    + (accessKeyId != null) + "]";
        }
    }

    /**
     * Transactional e-mail.
     *
     * @param mode {@code recording} (kept in memory, never sent; never in prod) or {@code resend}
     * @param apiKey the provider key, sent in a header only; required for resend; a secret
     * @param baseUrl the provider's API base URL ({@code https:})
     * @param from the sender, {@code Name <address>}
     * @param dnsVerified whether the sending domain's DKIM, SPF and DMARC records are published; the
     *     live sender sends nothing until it is true
     * @param testRecipients the only addresses the operator test e-mail may go to (lower-cased)
     * @param events per-event switches
     * @param caps messages per address
     * @param dispatcher the outbox dispatcher
     * @param connectTimeout connection timeout
     * @param readTimeout the wait for the provider's answer
     */
    public record Email(Mode mode, String apiKey, URI baseUrl, String from, boolean dnsVerified,
            List<String> testRecipients, Events events, Caps caps, Dispatcher dispatcher, Duration connectTimeout,
            Duration readTimeout) {

        public enum Mode { RECORDING, RESEND }

        public Email {
            mode = Objects.requireNonNullElse(mode, Mode.RECORDING);
            apiKey = StringUtils.hasText(apiKey) ? apiKey : null;
            if (mode == Mode.RESEND && apiKey == null) {
                throw new IllegalArgumentException("kybr.email.api-key is required when kybr.email.mode is resend");
            }
            requireHttps("kybr.email.base-url", baseUrl);
            if (!StringUtils.hasText(from)) {
                throw new IllegalArgumentException("kybr.email.from is required");
            }
            testRecipients = Objects.requireNonNullElse(testRecipients, List.<String>of()).stream()
                    .filter(StringUtils::hasText)
                    .map(address -> address.strip().toLowerCase(Locale.ROOT))
                    .toList();
            if (testRecipients.stream().anyMatch(address -> !address.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) {
                throw new IllegalArgumentException("kybr.email.test-recipients must be e-mail addresses");
            }
            events = Objects.requireNonNullElseGet(events, () -> new Events(null));
            if (mode == Mode.RESEND && !dnsVerified && events.contatoAlterado().enabled()) {
                // the live sender sends nothing until the domain is verified; an enabled notice would fail for good
                throw new IllegalArgumentException("kybr.email.events.contato-alterado.enabled needs "
                        + "kybr.email.dns-verified=true when kybr.email.mode is resend");
            }
            caps = Objects.requireNonNullElseGet(caps, () -> new Caps(0, 0));
            dispatcher = Objects.requireNonNullElseGet(dispatcher, () -> new Dispatcher(null, null, null, null));
            requirePositive("kybr.email.connect-timeout", connectTimeout);
            requirePositive("kybr.email.read-timeout", readTimeout);
        }

        /** @param contatoAlterado the notice to a seller's previous address after a contact change */
        public record Events(Toggle contatoAlterado) {
            public Events {
                contatoAlterado = Objects.requireNonNullElseGet(contatoAlterado, () -> new Toggle(false));
            }
        }

        public record Toggle(boolean enabled) {
        }

        /** At most {@code perHour} and {@code perDay} messages per address; more are dropped. */
        public record Caps(int perHour, int perDay) {
            public Caps {
                requirePositive("kybr.email.caps.per-hour", perHour);
                requirePositive("kybr.email.caps.per-day", perDay);
                if (perHour > perDay) {
                    throw new IllegalArgumentException("kybr.email.caps.per-hour must not exceed per-day");
                }
            }
        }

        /**
         * @param enabled whether this machine runs the scheduled dispatcher (default true); off only in
         *     tests, which trigger it on demand; prod refuses to start with it off
         * @param interval how often due events are claimed
         * @param lease how long a claim holds an event
         * @param backoff the wait before each retry of a retryable failure, in order; after the last
         *     one the event fails
         */
        public record Dispatcher(Boolean enabled, Duration interval, Duration lease, List<Duration> backoff) {
            public Dispatcher {
                enabled = Objects.requireNonNullElse(enabled, Boolean.TRUE);
                requirePositive("kybr.email.dispatcher.interval", interval);
                requirePositive("kybr.email.dispatcher.lease", lease);
                backoff = List.copyOf(Objects.requireNonNullElse(backoff, List.of()));
                if (backoff.isEmpty() || backoff.stream().anyMatch(d -> d.isNegative() || d.isZero())) {
                    throw new IllegalArgumentException("kybr.email.dispatcher.backoff needs at least one positive duration");
                }
            }
        }

        /** Never prints the key. */
        @Override
        public String toString() {
            return "Email[mode=" + mode + ", from=" + from + ", dnsVerified=" + dnsVerified + "]";
        }
    }

    /**
     * Document retention.
     *
     * @param enabled whether this machine runs the scheduled retention jobs (default true); off only in
     *     tests, which trigger them on demand; prod refuses to start with it off
     * @param backstop the retention date given to a never-decided case: this long after the seller's
     *     last activity
     * @param interval how often due cases are claimed
     * @param backstopInterval how often the backstop dates are recomputed
     * @param orphanInterval how often stored objects without a record are looked for
     * @param orphanMinAge an orphaned object younger than this is kept (it may be mid-upload)
     * @param lease how long a claim holds a case
     */
    public record Retention(Boolean enabled, Period backstop, Duration interval, Duration backstopInterval,
            Duration orphanInterval, Duration orphanMinAge, Duration lease) {
        public Retention {
            enabled = Objects.requireNonNullElse(enabled, Boolean.TRUE);
            if (backstop == null || backstop.isNegative() || backstop.isZero()) {
                throw new IllegalArgumentException("kybr.retention.backstop must be a positive period");
            }
            requirePositive("kybr.retention.interval", interval);
            requirePositive("kybr.retention.backstop-interval", backstopInterval);
            requirePositive("kybr.retention.orphan-interval", orphanInterval);
            requirePositive("kybr.retention.orphan-min-age", orphanMinAge);
            requirePositive("kybr.retention.lease", lease);
        }
    }

    /**
     * The asynchronous scan job: received files are claimed from quarantine, scanned, re-encoded
     * (photos) and accepted or refused.
     *
     * @param enabled whether this machine schedules the job and starts it after an upload (default
     *     true); off only in tests, which run it on demand; prod refuses to start with it off
     * @param workers files scanned at once per machine
     * @param pollInterval how often due and abandoned files are looked for (a missed nudge, a lost lease)
     * @param wakeTimeout how long a session waits for a stopped scanner to answer; at most 120 s
     * @param wakeInterval the pause between wake-up probes
     * @param lease how long a claim holds a file
     * @param maxAttempts scans attempted before a file is refused as not verifiable
     * @param backoff the wait before each retry, by attempt (the last value repeats)
     * @param stuckAfter a file still pending after this long is reported stuck
     */
    public record Scan(Boolean enabled, Integer workers, Duration pollInterval, Duration wakeTimeout,
            Duration wakeInterval, Duration lease, Integer maxAttempts, List<Duration> backoff, Duration stuckAfter) {

        static final Duration MAX_WAKE_TIMEOUT = Duration.ofSeconds(120);

        public Scan {
            enabled = Objects.requireNonNullElse(enabled, Boolean.TRUE);
            workers = Objects.requireNonNullElse(workers, 2);
            pollInterval = Objects.requireNonNullElse(pollInterval, Duration.ofSeconds(10));
            wakeTimeout = Objects.requireNonNullElse(wakeTimeout, MAX_WAKE_TIMEOUT);
            wakeInterval = Objects.requireNonNullElse(wakeInterval, Duration.ofSeconds(5));
            lease = Objects.requireNonNullElse(lease, Duration.ofMinutes(5));
            maxAttempts = Objects.requireNonNullElse(maxAttempts, 6);
            backoff = List.copyOf(Objects.requireNonNullElse(backoff, List.of(Duration.ofMinutes(1),
                    Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(3))));
            stuckAfter = Objects.requireNonNullElse(stuckAfter, Duration.ofHours(24));
            requirePositive("kybr.scan.workers", workers);
            requirePositive("kybr.scan.poll-interval", pollInterval);
            requirePositive("kybr.scan.wake-timeout", wakeTimeout);
            if (wakeTimeout.compareTo(MAX_WAKE_TIMEOUT) > 0) {
                throw new IllegalArgumentException("kybr.scan.wake-timeout must be at most 120s");
            }
            requirePositive("kybr.scan.wake-interval", wakeInterval);
            requirePositive("kybr.scan.lease", lease);
            requirePositive("kybr.scan.max-attempts", maxAttempts);
            if (backoff.isEmpty() || backoff.stream().anyMatch(wait -> wait.isNegative() || wait.isZero())) {
                throw new IllegalArgumentException("kybr.scan.backoff must list positive durations");
            }
            requirePositive("kybr.scan.stuck-after", stuckAfter);
        }

        /** The wait before the retry that follows attempt {@code attempt} (from 1). */
        public Duration backoffAfter(int attempt) {
            return backoff.get(Math.min(Math.max(attempt, 1), backoff.size()) - 1);
        }
    }

    /** Cross-origin access; only the seller document upload has any. */
    public record Cors(D6Cors d6) {
        public Cors {
            d6 = Objects.requireNonNullElseGet(d6, () -> new D6Cors(null, null));
        }
    }

    /**
     * The origins the seller's browser may upload documents from. Never credentials, never a
     * wildcard.
     *
     * @param origins exact origins: {@code https://host[:port]}, or {@code http:} for localhost only
     * @param originPatterns at most one regular expression, anchored ({@code ^https://…$}), for a
     *     frontend preview scope; never in prod
     */
    public record D6Cors(List<String> origins, List<String> originPatterns) {
        private static final Pattern ORIGIN =
                Pattern.compile("https://[a-z0-9.-]+(?::[0-9]{1,5})?|http://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]{1,5})?");

        public D6Cors {
            origins = Objects.requireNonNullElse(origins, List.<String>of()).stream()
                    .filter(StringUtils::hasText).map(String::strip).toList();
            originPatterns = Objects.requireNonNullElse(originPatterns, List.<String>of()).stream()
                    .filter(StringUtils::hasText).map(String::strip).toList();
            if (origins.stream().anyMatch(origin -> !ORIGIN.matcher(origin).matches())) {
                throw new IllegalArgumentException(
                        "kybr.cors.d6.origins must be exact origins (https://host[:port], http: for localhost only)");
            }
            if (originPatterns.size() > 1) {
                throw new IllegalArgumentException("kybr.cors.d6.origin-patterns takes at most one pattern");
            }
            for (String pattern : originPatterns) {
                if (!pattern.startsWith("^https://") || !pattern.endsWith("$")) {
                    throw new IllegalArgumentException("kybr.cors.d6.origin-patterns must be anchored: ^https://...$");
                }
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("kybr.cors.d6.origin-patterns must be a valid regular expression");
                }
            }
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

    private static void requirePort(String property, Integer value) {
        if (value == null || value < 1 || value > 65_535) {
            throw new IllegalArgumentException(property + " must be a TCP port");
        }
    }

    private static void requireLimit(String property, LimitWindow window) {
        if (window == null) {
            throw new IllegalArgumentException(property + " is required");
        }
    }

    /** A base64 SHA-256 digest (32 bytes). */
    private static void requireSha256(String property, String value) {
        if (value == null) {
            throw new IllegalArgumentException(property + " is required");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(property + " must be base64");
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException(property + " must be a SHA-256 digest");
        }
    }

    /** One PEM block of type {@code label}. The content is checked when it is used. */
    private static void requirePem(String property, String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(property + " is required");
        }
        String pem = value.strip();
        if (!pem.startsWith("-----BEGIN " + label + "-----") || !pem.endsWith("-----END " + label + "-----")) {
            throw new IllegalArgumentException(property + " must be a PEM " + label.toLowerCase(Locale.ROOT));
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
