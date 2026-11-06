package com.matheusmelo.kybr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.BffKeySlot;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BffKeyVerifierIT extends WebIntegrationTest {

    @Autowired
    private BffKeyVerifier verifier;

    @Autowired
    private KybrProperties properties;

    @Test
    void theBeanVerifiesTheConfiguredKey() {
        assertThat(verifier.verify(properties.bff().current())).isEqualTo(BffKeySlot.CURRENT);
        assertThat(verifier.verify(properties.bff().current() + "x")).isEqualTo(BffKeySlot.NONE);
    }
}
