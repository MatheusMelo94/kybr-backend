package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.matheusmelo.kybr.support.ProfileBoot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

/** The remote sanitizer needs its address and the scan link's TLS material in every profile, not only prod. */
class ImageModeStartupTest {

    private static List<String> stagingWithRemoteSanitizer() {
        List<String> env = new ArrayList<>(ProfileBoot.validCloudEnv());
        env.add("--KYBR_IMAGE_MODE=remote");
        return env;
    }

    private static String failure(List<String> env) {
        Throwable failure = catchThrowable(() -> ProfileBoot.start("staging", env.toArray(String[]::new)).close());
        assertThat(failure).isNotNull();
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }

    @Test
    void remoteModeStartsWithAnAddressAndTheLinkMaterial() {
        try (ConfigurableApplicationContext context =
                ProfileBoot.start("staging", stagingWithRemoteSanitizer().toArray(String[]::new))) {
            assertThat(context.getBean(KybrProperties.class).image().mode()).isEqualTo(KybrProperties.Image.Mode.REMOTE);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"KYBR_SANITIZER_HOST", "KYBR_SANITIZER_PORT"})
    void remoteModeRefusesToStartWithoutTheSanitizersAddress(String variable) {
        List<String> env = stagingWithRemoteSanitizer();
        env.removeIf(arg -> arg.startsWith("--" + variable + "="));

        String property = variable.equals("KYBR_SANITIZER_HOST") ? "kybr.sanitizer.host" : "kybr.sanitizer.port";
        assertThat(failure(env)).contains(property).contains("kybr.image.mode");
    }

    @ParameterizedTest
    @ValueSource(strings = {"KYBR_SCANNER_CLIENT_CERT", "KYBR_SCANNER_CLIENT_KEY", "KYBR_SCANNER_CA",
        "KYBR_SCANNER_SPKI_SHA256"})
    void remoteModeRefusesToStartWithoutTheScanLinkMaterialEvenWithTheStubScanner(String variable) {
        List<String> env = stagingWithRemoteSanitizer();
        env.removeIf(arg -> arg.startsWith("--" + variable + "="));

        String property = "kybr.scanner." + variable.substring("KYBR_SCANNER_".length()).toLowerCase().replace('_', '-');
        assertThat(failure(env)).contains(property);
    }

    @Test
    void theInProcessStandInNeedsNoSanitizerAddress() {
        List<String> env = new ArrayList<>(ProfileBoot.validCloudEnv());
        env.removeIf(arg -> arg.startsWith("--KYBR_SANITIZER_"));

        try (ConfigurableApplicationContext context = ProfileBoot.start("staging", env.toArray(String[]::new))) {
            assertThat(context.getBean(KybrProperties.class).image().mode())
                    .isEqualTo(KybrProperties.Image.Mode.IN_PROCESS_TEST);
        }
    }
}
