package com.matheusmelo.kybr.config.logging;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.util.LogRedactor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The context's {@link LogRedactor}, for components that redact before they log (the access log):
 * built from this context's {@code kybr.*} settings and {@link KeyHasher}, independent of the
 * process-wide {@link LogRedaction} backstop, with the same secret literals
 * ({@link LogRedaction#secretLiterals}).
 */
@Configuration(proxyBeanMethods = false)
public class LogRedactorConfig {

    @Bean
    LogRedactor logRedactor(KybrProperties properties, KeyHasher keyHasher, Environment environment) {
        return new LogRedactor(properties.logging().tokenPrefixes(), LogRedaction.secretLiterals(environment),
                keyHasher::hmac);
    }
}
