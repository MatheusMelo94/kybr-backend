package com.matheusmelo.kybr.support;

import com.matheusmelo.kybr.config.logging.LogRedaction;
import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Supplies the in-memory {@link TestSecrets} to every {@link MongoIntegrationTest} context and
 * rebuilds the process-wide console {@link LogRedaction} from them. In production
 * the secrets are env vars, present when the environment is prepared; a test's secrets arrive
 * with the context, after logging has started, so without this the console redactor would run
 * without its literal-key backstop and with a random token-hash key.
 *
 * <p>A context customizer (registered in {@code META-INF/spring.factories}) rather than a
 * {@code @ContextConfiguration} initializer, which would stop Boot detecting a subclass's nested
 * {@code @TestConfiguration}.
 */
public class TestSecretsContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        return MongoIntegrationTest.class.isAssignableFrom(testClass) ? new TestSecretsCustomizer() : null;
    }

    private static final class TestSecretsCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            ConfigurableEnvironment environment = context.getEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("kybrTestSecrets", Map.of(
                    "kybr.bff.current", TestSecrets.BFF_KEY_CURRENT,
                    "kybr.hashing.secret", TestSecrets.HASH_SECRET,
                    "kybr.idempotency.enc-key", TestSecrets.IDEMPOTENCY_ENC_KEY)));
            LogRedaction.configure(environment);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestSecretsCustomizer;
        }

        @Override
        public int hashCode() {
            return TestSecretsCustomizer.class.hashCode();
        }
    }
}
