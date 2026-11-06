package com.matheusmelo.kybr.support;

import com.matheusmelo.kybr.config.KybrProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Starts a minimal, non-web context that loads the real {@code application*.yml} files for a
 * profile and binds {@link KybrProperties}. Fast enough to run per case, with no MongoDB.
 *
 * <p>Arguments are passed as command-line properties ({@code --NAME=value}), so they win over
 * the yml files exactly as real environment variables do.
 */
public final class ProfileBoot {

    private ProfileBoot() {
    }

    public static ConfigurableApplicationContext start(String profile, String... args) {
        return new SpringApplicationBuilder(PropertiesOnly.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .profiles(profile)
                .run(args);
    }

    /** The environment a staging or prod machine has when every required secret is set. */
    public static List<String> validCloudEnv() {
        List<String> args = new ArrayList<>();
        args.add("--MONGODB_URI=mongodb://127.0.0.1:1/kybr?replicaSet=rs0");
        args.add("--KYBR_BFF_KEY_CURRENT=" + TestSecrets.BFF_KEY_CURRENT);
        args.add("--KYBR_HASH_SECRET=" + TestSecrets.HASH_SECRET);
        args.add("--KYBR_IDEMPOTENCY_ENC_KEY=" + TestSecrets.IDEMPOTENCY_ENC_KEY);
        return args;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KybrProperties.class)
    static class PropertiesOnly {
    }
}
