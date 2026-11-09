package com.matheusmelo.kybr.arch.fixture.upload;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Live client beans, gated and not. The stereotypes sit on non-static inner classes, which
 * component scanning never registers, so no test context picks them up.
 */
public class LiveClientBeans {

    @Configuration
    public class Beans {

        @Bean
        FixtureLiveClient unguarded() {
            return new FixtureLiveClient();
        }

        @Bean
        @ConditionalOnProperty(prefix = "kybr.storage", name = "mode", havingValue = "s3")
        FixtureLiveClient guarded() {
            return new FixtureLiveClient();
        }
    }

    @Component
    public class UnguardedComponent extends FixtureLiveClient {
    }

    @Component
    @ConditionalOnProperty(prefix = "kybr.email", name = "mode", havingValue = "recording")
    public class WrongModeComponent extends FixtureLiveClient {
    }

    @Component
    @ConditionalOnProperty(prefix = "kybr.email", name = "mode", havingValue = "resend")
    public class GuardedComponent extends FixtureLiveClient {
    }
}
