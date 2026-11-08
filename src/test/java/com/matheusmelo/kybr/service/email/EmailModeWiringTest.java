package com.matheusmelo.kybr.service.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.support.ProfileBoot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The e-mail mode picks exactly one sender bean, without any provider call at startup. */
class EmailModeWiringTest {

    @Test
    void prodWiresOnlyTheLiveSender() {
        try (ConfigurableApplicationContext context = start("prod")) {
            assertThat(context.getBeansOfType(EmailSender.class).values()).singleElement()
                    .isInstanceOf(ResendEmailSender.class);
        }
    }

    @Test
    void stagingWiresOnlyTheRecordingSender() {
        try (ConfigurableApplicationContext context = start("staging")) {
            assertThat(context.getBeansOfType(EmailSender.class).values()).singleElement()
                    .isInstanceOf(RecordingEmailSender.class);
        }
    }

    private static ConfigurableApplicationContext start(String profile) {
        List<String> args = new ArrayList<>(ProfileBoot.validCloudEnv());
        return new SpringApplicationBuilder(Senders.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .profiles(profile)
                .run(args.toArray(String[]::new));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KybrProperties.class)
    @Import({RecordingEmailSender.class, ResendEmailSender.class})
    static class Senders {
    }
}
