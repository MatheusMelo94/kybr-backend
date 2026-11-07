package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.support.ProfileBoot;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The provider mode picks exactly one provider bean, without any provider call at startup. */
class ProviderModeWiringTest {

    @Test
    void liveModeWiresOnlyTheLiveClient() {
        try (ConfigurableApplicationContext context = start("prod")) {
            assertThat(context.getBeansOfType(CompanyDataProvider.class).values()).singleElement()
                    .isInstanceOf(CnpjaCompanyDataProvider.class);
        }
    }

    @Test
    void stubModeWiresOnlyTheStub() {
        try (ConfigurableApplicationContext context = start("staging")) {
            assertThat(context.getBeansOfType(CompanyDataProvider.class).values()).singleElement()
                    .isInstanceOf(StubCompanyDataProvider.class);
        }
    }

    private static ConfigurableApplicationContext start(String profile) {
        return new SpringApplicationBuilder(Providers.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .profiles(profile)
                .run(ProfileBoot.validCloudEnv().toArray(String[]::new));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KybrProperties.class)
    @Import({StubCompanyDataProvider.class, CnpjaCompanyDataProvider.class})
    static class Providers {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
