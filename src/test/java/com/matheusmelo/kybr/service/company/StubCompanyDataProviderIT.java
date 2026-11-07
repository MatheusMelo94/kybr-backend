package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.service.company.CompanyDataResult.Found;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StubCompanyDataProviderIT extends MongoIntegrationTest {

    @Autowired
    private CompanyDataProvider provider;

    @Test
    void stubModeWiresTheFixtureStubAsTheProvider() {
        assertThat(provider).isInstanceOf(StubCompanyDataProvider.class);
        assertThat(provider.fullCheck("12345678000195")).isInstanceOf(Found.class);
    }
}
