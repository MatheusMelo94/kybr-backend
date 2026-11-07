package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.filter.RequestIdFilter;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.ApplicationContext;

class HealthProbesIT extends WebIntegrationTest {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ApplicationContext context;

    private Response management(String path) {
        return RestAssured.given().port(managementPort).get(path);
    }

    @Test
    void livezIsStatusOnlyWithoutAKeyAndCarriesThePipelineHeaders() {
        Response response = httpWithoutKey().get("/livez");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(response.header(RequestIdFilter.HEADER)).matches("[0-9a-f-]{36}");
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void readyzDoesNotExistOnTheMainPort() {
        assertThat(httpWithoutKey().get("/readyz").statusCode()).isEqualTo(403);
        assertThat(http().get("/readyz").statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/health", "/actuator/health/readiness", "/actuator/prometheus",
        "/actuator/env"})
    void actuatorIsNotOnTheMainPort(String path) {
        assertThat(httpWithoutKey().get(path).statusCode()).isEqualTo(403);
        Response withKey = http().get(path);
        assertThat(withKey.statusCode()).isEqualTo(404);
        assertThat(withKey.jsonPath().getString("code")).isEqualTo("RECURSO_NAO_ENCONTRADO");
    }

    @Test
    void readinessOnTheManagementPortIsStatusOnly() {
        Response response = management("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void notReadyIsActuators503BodyNotTheEnvelope() {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        try {
            Response response = management("/actuator/health/readiness");

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.asString()).isEqualTo("{\"status\":\"OUT_OF_SERVICE\"}");
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
        assertThat(management("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void theManagementPortServesPrometheusAndNothingElse() {
        Response prometheus = management("/actuator/prometheus");

        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.contentType()).startsWith("text/plain");
        assertThat(management("/actuator/env").statusCode()).isEqualTo(403);
        assertThat(management("/actuator/heapdump").statusCode()).isEqualTo(403);
    }

    @Test
    void readinessPingsMongoAtMostTwiceForAHundredCallsInTwoSeconds() {
        CachedHealthIndicator mongo = context.getBean("mongoHealthContributor", CachedHealthIndicator.class);
        long before = mongo.checks();

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            management("/actuator/health/readiness");
        }

        // At most one ping per started 2 s window, plus the first; 2 when the calls fit in 2 s.
        long windows = (System.nanoTime() - start) / 2_000_000_000L;
        assertThat(mongo.checks() - before).isLessThanOrEqualTo(2 + windows);
        assertThat(context.getBean("mongoHealthContributor")).isInstanceOf(HealthIndicator.class);
    }

    @Test
    @Disabled("Needs Docker to pause MongoDB (the external rs0 used without Docker is shared). Covered by"
            + " CachedHealthIndicatorTest (a failing delegate is DOWN, recovery after expiry) and a manual check:"
            + " /livez stays 200 and readiness is 503 while MongoDB is paused, 200 after unpause.")
    void livezStaysUpAndReadinessGoesDownWhileMongoIsPaused() {
    }
}
