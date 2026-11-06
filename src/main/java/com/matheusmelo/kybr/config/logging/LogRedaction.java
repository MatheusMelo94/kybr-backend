package com.matheusmelo.kybr.config.logging;

import com.matheusmelo.kybr.config.KybrProperties.Hashing;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.util.LogRedactor;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The process-wide {@link LogRedactor} used by the logging pipeline: the ECS JSON
 * customizer and the pattern converter. Logging is configured before (and outside) the
 * application context, so this is a static holder, configured from the {@link Environment} by
 * {@link LogRedactionEnvironmentListener} as soon as the config files are loaded.
 *
 * <p>Until then, and if {@code kybr.hashing.secret} is missing or malformed, token hashes use a
 * random per-process key: still keyed (never a plain hash), only without cross-process
 * correlation.
 */
public final class LogRedaction {

    private static final List<String> DEFAULT_PREFIXES = List.of("cases");
    private static final List<String> SECRET_PROPERTIES = List.of("kybr.bff.current", "kybr.bff.previous",
            "kybr.hashing.secret", "kybr.hashing.previous", "kybr.idempotency.enc-key");
    /** The password in {@code scheme://user:password@host}: group 1. */
    private static final Pattern URI_CREDENTIALS = Pattern.compile("://[^:@/\\s]*:([^@/\\s]+)@");

    private static volatile LogRedactor current = new LogRedactor(DEFAULT_PREFIXES, List.of(), randomKeyHasher());

    private LogRedaction() {
    }

    public static String redact(String text) {
        return current.redact(text);
    }

    /** (Re)builds the redactor from {@code kybr.*}: token prefixes, BFF keys and the hash secret. */
    public static void configure(Environment environment) {
        current = from(environment);
    }

    static LogRedactor from(Environment environment) {
        List<String> prefixes = Binder.get(environment)
                .bind("kybr.logging.token-prefixes", Bindable.listOf(String.class))
                .orElse(DEFAULT_PREFIXES);
        return new LogRedactor(prefixes, secretLiterals(environment),
                keyHasher(environment.getProperty("kybr.hashing.secret")));
    }

    /**
     * The configured secret values, masked verbatim wherever they appear in a log line: the BFF
     * keys, the hash secrets, the replay-body keyset and the MongoDB password (as written in the
     * URI and percent-decoded). The user name is not a secret and stays readable.
     */
    public static List<String> secretLiterals(Environment environment) {
        List<String> literals = new ArrayList<>();
        for (String property : SECRET_PROPERTIES) {
            add(literals, environment.getProperty(property));
        }
        Matcher credentials = URI_CREDENTIALS.matcher(environment.getProperty("spring.mongodb.uri", ""));
        if (credentials.find()) {
            String password = credentials.group(1);
            add(literals, password);
            try {
                add(literals, URLDecoder.decode(password, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException malformedEscape) {
                // the raw form is already masked
            }
        }
        return literals.stream().distinct().toList();
    }

    private static void add(List<String> literals, String value) {
        if (value != null && !value.isBlank()) {
            literals.add(value);
        }
    }

    private static UnaryOperator<String> keyHasher(String secret) {
        try {
            return new KeyHasher(new Hashing(secret, null))::hmac;
        } catch (IllegalArgumentException e) {
            return randomKeyHasher();
        }
    }

    private static UnaryOperator<String> randomKeyHasher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new KeyHasher(new Hashing(Base64.getUrlEncoder().withoutPadding().encodeToString(key), null))::hmac;
    }
}
