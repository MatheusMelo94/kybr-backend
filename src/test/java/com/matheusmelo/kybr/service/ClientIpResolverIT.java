package com.matheusmelo.kybr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.BffKeySlot;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ClientIpResolverIT extends WebIntegrationTest {

    @Autowired
    private ClientIpResolver resolver;

    @Test
    void theTestProfileUsesTheSocketAddressAndIgnoresAPlatformHeader() {
        assertThat(resolver.platformHeader()).isNull();

        ClientIp ip = resolver.resolve(BffKeySlot.NONE, List.of(), List.of("203.0.113.8"), "192.0.2.10").clientIp();

        assertThat(ip).isEqualTo(new ClientIp("192.0.2.10", ClientIp.Source.DIRECT));
    }
}
