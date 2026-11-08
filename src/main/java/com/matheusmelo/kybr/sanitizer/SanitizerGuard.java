package com.matheusmelo.kybr.sanitizer;

import java.util.List;
import java.util.Locale;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * The sanitizer holds no secret that opens anything else. It refuses to start when any setting of
 * the API, a data store or a cloud credential is present: Kybr settings and secrets, MongoDB, AWS
 * and the e-mail provider. Names are reported, values never.
 */
final class SanitizerGuard {

    private static final List<String> FORBIDDEN_PREFIXES = List.of("kybr.", "kybr_", "mongodb_", "mongodb.",
            "spring.mongodb.", "spring_mongodb_", "spring.data.mongodb.", "spring_data_mongodb_", "aws_", "aws.",
            "resend_", "resend.");

    private SanitizerGuard() {
    }

    /**
     * @throws IllegalStateException naming the first forbidden setting found
     */
    static void check(ConfigurableEnvironment environment) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (FORBIDDEN_PREFIXES.stream().anyMatch(lower::startsWith)) {
                        throw new IllegalStateException(name + " must not be set for the sanitizer: it holds no"
                                + " data-store setting or secret");
                    }
                }
            }
        }
    }
}
