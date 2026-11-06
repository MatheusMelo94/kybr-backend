package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.ProfileBoot;
import com.matheusmelo.kybr.support.web.StagingWebIntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

/** API docs are off in the cloud profiles: an unknown route, behind the key. */
class OpenApiDisabledIT extends StagingWebIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config"})
    void theDocsAre404WithAKeyAnd403Without(String path) {
        Response withKey = http().get(path);

        assertThat(withKey.statusCode()).isEqualTo(404);
        assertThat(withKey.jsonPath().getString("code")).isEqualTo("RECURSO_NAO_ENCONTRADO");
        assertThat(httpWithoutKey().get(path).statusCode()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"staging", "prod"})
    void theCloudProfilesDisableTheDocs(String profile) {
        try (ConfigurableApplicationContext context =
                ProfileBoot.start(profile, ProfileBoot.validCloudEnv().toArray(String[]::new))) {
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
        }
    }
}
