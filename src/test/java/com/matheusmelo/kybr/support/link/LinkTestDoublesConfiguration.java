package com.matheusmelo.kybr.support.link;

import com.matheusmelo.kybr.service.company.CompanyDataProvider;
import com.matheusmelo.kybr.service.verification.VerificationDispatcher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Makes {@link FakeCompanyDataProvider} and {@link RecordingDispatcher} the primary
 * {@link CompanyDataProvider} and {@link VerificationDispatcher} beans, ahead of the production
 * ones. Opt in with {@code @Import(LinkTestDoublesConfiguration.class)} on a test base class; every
 * class sharing that base shares one cached context and the same doubles. Never scanned.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LinkTestDoublesConfiguration {

    @Bean
    @Primary
    FakeCompanyDataProvider fakeCompanyDataProvider() {
        return new FakeCompanyDataProvider();
    }

    @Bean
    @Primary
    RecordingDispatcher recordingDispatcher() {
        return new RecordingDispatcher();
    }
}
