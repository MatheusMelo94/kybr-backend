package com.matheusmelo.kybr.sanitizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;

/** The running API holds nothing of the sanitizer: no bean from its package and no image decoder. */
class SanitizerIsolationIT extends WebIntegrationTest {

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void theApiContextHasNoSanitizerOrDecoderBean() {
        assertThat(Arrays.stream(context.getBeanDefinitionNames())
                .map(name -> context.getBeanFactory().getType(name, false))
                .filter(Objects::nonNull)
                .map(Class::getName))
                .isNotEmpty()
                .noneMatch(type -> type.startsWith("com.matheusmelo.kybr.sanitizer."))
                .noneMatch(type -> type.startsWith("javax.imageio.") || type.startsWith("java.awt."));
        assertThat(context.getBeanNamesForType(ImageSanitizer.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SanitizerServer.class)).isEmpty();
    }
}
