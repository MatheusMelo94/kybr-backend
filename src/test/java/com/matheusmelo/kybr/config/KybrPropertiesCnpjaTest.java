package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.matheusmelo.kybr.config.KybrProperties.Cnpja;
import com.matheusmelo.kybr.config.KybrProperties.CreditCosts;
import com.matheusmelo.kybr.config.KybrProperties.Timeouts;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KybrPropertiesCnpjaTest {

    @Test
    void acceptsTheAuthHeaderAndTheFullCheckAddOns() {
        Cnpja cnpja = cnpja("Authorization", new Cnpja.Addons(true, "BR"));

        assertThat(cnpja.authHeader()).isEqualTo("Authorization");
        assertThat(cnpja.addons()).isEqualTo(new Cnpja.Addons(true, "BR"));
    }

    @Test
    void acceptsAListOfStatesForTheRegistrationAddOn() {
        assertThat(cnpja("Authorization", new Cnpja.Addons(false, "SP,RJ")).addons().registrations()).isEqualTo("SP,RJ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Bad Header", "X-Key:", "Authorization\n"})
    void refusesAMissingOrMalformedAuthHeader(String header) {
        assertThatIllegalArgumentException().isThrownBy(() -> cnpja(header, new Cnpja.Addons(true, "BR")))
                .withMessageContaining("kybr.cnpja.auth-header");
    }

    @Test
    void refusesMissingAddOns() {
        assertThatIllegalArgumentException().isThrownBy(() -> cnpja("Authorization", null))
                .withMessageContaining("kybr.cnpja.addons");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "br", "BR,", "SPX", "SP;RJ"})
    void refusesMalformedRegistrationStates(String registrations) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Cnpja.Addons(true, registrations))
                .withMessageContaining("kybr.cnpja.addons.registrations");
    }

    private static Cnpja cnpja(String authHeader, Cnpja.Addons addons) {
        return new Cnpja(Cnpja.Mode.STUB, null, URI.create("https://provider.example.test"),
                new Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(8)), 45,
                "CACHE_IF_FRESH", new CreditCosts(1, 1, 1), 10, 10_000L, authHeader, addons);
    }
}
