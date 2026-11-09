package com.matheusmelo.kybr.support.email;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Makes {@link ScriptedEmailSender} the primary sender, ahead of the recording one. Opt in with
 * {@code @Import(EmailTestDoublesConfiguration.class)} on a test base class; every class sharing that
 * base shares one cached context and the same double, so reset it between tests. Never scanned.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmailTestDoublesConfiguration {

    @Bean
    @Primary
    ScriptedEmailSender scriptedEmailSender() {
        return new ScriptedEmailSender();
    }
}
