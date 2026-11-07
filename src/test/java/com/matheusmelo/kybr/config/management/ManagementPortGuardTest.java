package com.matheusmelo.kybr.config.management;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ManagementPortGuardTest {

    private static MockEnvironment exposing(String include) {
        return new MockEnvironment().withProperty("management.endpoints.web.exposure.include", include);
    }

    @Test
    void refusesToStartWhenAdminIsExposedWithoutAManagementPort() {
        MockEnvironment environment = exposing("health,prometheus,admin").withProperty("server.port", "8080");

        assertThatIllegalStateException().isThrownBy(() -> ManagementPortGuard.check(environment))
                .withMessageContaining("management.server.port");
    }

    @Test
    void refusesToStartWhenTheManagementPortEqualsThePublicPort() {
        MockEnvironment environment = exposing("health,prometheus,admin")
                .withProperty("server.port", "8080").withProperty("management.server.port", "8080");

        assertThatIllegalStateException().isThrownBy(() -> ManagementPortGuard.check(environment))
                .withMessageContaining("management.server.port");
    }

    @Test
    void refusesToStartWhenEveryEndpointIsExposedOnThePublicPort() {
        MockEnvironment environment = exposing("*").withProperty("server.port", "8080")
                .withProperty("management.server.port", "8080");

        assertThatIllegalStateException().isThrownBy(() -> ManagementPortGuard.check(environment));
    }

    @Test
    void refusesToStartWhenTheManagementPortIsTheDefaultPublicPort() {
        MockEnvironment environment = exposing("admin").withProperty("management.server.port", "8080");

        assertThatIllegalStateException().isThrownBy(() -> ManagementPortGuard.check(environment));
    }

    @Test
    void startsWithASeparateManagementPort() {
        MockEnvironment environment = exposing("health,prometheus,admin")
                .withProperty("server.port", "8080").withProperty("management.server.port", "8081");

        assertThatCode(() -> ManagementPortGuard.check(environment)).doesNotThrowAnyException();
    }

    @Test
    void startsWithTwoRandomPortsWhichAreAlwaysDistinct() {
        MockEnvironment environment = exposing("health,prometheus,admin")
                .withProperty("server.port", "0").withProperty("management.server.port", "0");

        assertThatCode(() -> ManagementPortGuard.check(environment)).doesNotThrowAnyException();
    }

    @Test
    void doesNotCareAboutThePortWhenAdminIsNotExposed() {
        MockEnvironment environment = exposing("health,prometheus").withProperty("server.port", "8080");

        assertThatCode(() -> ManagementPortGuard.check(environment)).doesNotThrowAnyException();
    }
}
