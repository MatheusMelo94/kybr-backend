package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.ProfileGuards;
import com.matheusmelo.kybr.support.ProfileBoot;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void prodNeverRegistersTheStubEvenInStubMode() {
        try (ConfigurableApplicationContext context = start(Providers.class, "prod", "--kybr.cnpja.mode=stub")) {
            assertThat(context.getBeansOfType(StubCompanyDataProvider.class)).isEmpty();
        }
    }

    @Test
    void prodInStubModeFailsStartupOnTheGuard() {
        Throwable failure = catchThrowable(() -> start(GuardedProviders.class, "prod", "--kybr.cnpja.mode=stub").close());

        assertThat(failure).rootCause().hasMessageContaining("kybr.cnpja.mode must be live");
    }

    private static ConfigurableApplicationContext start(String profile) {
        return start(Providers.class, profile);
    }

    private static ConfigurableApplicationContext start(Class<?> source, String profile, String... extra) {
        List<String> args = new ArrayList<>(ProfileBoot.validCloudEnv());
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(source)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .profiles(profile)
                .run(args.toArray(String[]::new));
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

    @Configuration(proxyBeanMethods = false)
    @Import({Providers.class, ProfileGuards.class})
    static class GuardedProviders {
    }
}
