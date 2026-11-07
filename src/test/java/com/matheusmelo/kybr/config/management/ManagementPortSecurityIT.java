package com.matheusmelo.kybr.config.management;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.matheusmelo.kybr.config.filter.RequestIdFilter;
import com.matheusmelo.kybr.support.TestSecrets;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import com.matheusmelo.kybr.support.web.ManagementProbeEndpoint;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;

class ManagementPortSecurityIT extends WebIntegrationTest {

    private static final String ADMIN = "/actuator/admin";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private MeterRegistry meters;

    private RequestSpecification management() {
        return RestAssured.given().port(managementPort);
    }

    private RequestSpecification withToken(String token) {
        return management().header("Authorization", "Bearer " + token);
    }

    private double authFailures() {
        return meters.get("kybr.admin.auth_failed").counter().count();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/readiness", "/actuator/prometheus"})
    void healthAndPrometheusNeedNoToken(String path) {
        Response response = management().get(path);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header(RequestIdFilter.HEADER)).matches(UUID_PATTERN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/" + ManagementProbeEndpoint.ID, "/actuator/env", "/actuator", "/anything",
        "/actuator/heapdump"})
    void everyOtherManagementPathIsDeniedWithoutAToken(String path) {
        Response response = management().get(path);

        assertThat(response.statusCode()).isIn(401, 403);
        assertThat(response.asString()).isEmpty();
        assertThat(response.header(RequestIdFilter.HEADER)).matches(UUID_PATTERN);
    }

    @Test
    void anEndpointNoRuleNamesStaysDeniedEvenWithTheAdminToken() {
        Response response = withToken(TestSecrets.ADMIN_TOKEN).get("/actuator/" + ManagementProbeEndpoint.ID);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.asString()).doesNotContain("reachable");
    }

    @Test
    void theAdminPathWithoutAValidTokenIs401WithNoDetailAndIsCountedAndLogged() {
        double before = authFailures();
        try (CapturedEvents log = CapturedEvents.of(AdminAuthFailureHandler.class)) {
            List<Response> responses = List.of(
                    management().get(ADMIN + "/x"),
                    withToken("not-the-token").get(ADMIN + "/x"),
                    // HTTP strips trailing whitespace from a header value, so a space inside is the
                    // variant that reaches the server; the verifier itself is byte-exact.
                    withToken(" " + TestSecrets.ADMIN_TOKEN).get(ADMIN + "/x"),
                    management().header("Authorization", TestSecrets.ADMIN_TOKEN).get(ADMIN + "/x"));

            assertThat(responses).allSatisfy(response -> {
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(response.asString()).isEmpty();
                assertThat(response.header(RequestIdFilter.HEADER)).matches(UUID_PATTERN);
            });
            assertThat(authFailures() - before).isEqualTo(4.0);
            assertThat(log.events()).hasSize(4).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("event=admin_auth_failed");
                assertThat(event.getFormattedMessage()).doesNotContain(TestSecrets.ADMIN_TOKEN);
            });
        }
    }

    @Test
    void theCurrentAndThePreviousTokenBothPassTheChain() {
        assertThat(withToken(TestSecrets.ADMIN_TOKEN).get(ADMIN + "/x").statusCode()).isNotIn(401, 403);
        assertThat(withToken(TestSecrets.ADMIN_TOKEN_PREVIOUS).get(ADMIN + "/x").statusCode()).isNotIn(401, 403);
    }

    @Test
    void aBodyOver16KibToTheAdminPathIs413() {
        Response response = withToken(TestSecrets.ADMIN_TOKEN).contentType("application/json")
                .body("{\"nome\":\"" + "a".repeat(17 * 1024) + "\"}").post(ADMIN + "/x");

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.header(RequestIdFilter.HEADER)).matches(UUID_PATTERN);
    }

    @Test
    void eachAdminCallWritesOneAccessLineWithItsSlotAndNeitherBodyNorToken() {
        String canary = "body-canary-" + System.nanoTime();
        try (CapturedEvents log = CapturedEvents.of(ManagementAccessLogFilter.class)) {
            Response response = withToken(TestSecrets.ADMIN_TOKEN_PREVIOUS).contentType("application/json")
                    .body("{\"nome\":\"" + canary + "\"}").post(ADMIN + "/x");
            management().get("/actuator/prometheus");

            List<ILoggingEvent> lines = log.events();
            assertThat(lines).hasSize(1);
            String line = lines.getFirst().getFormattedMessage();
            assertThat(line).contains("event=admin_access", "method=POST", "operation=" + ADMIN + "/{operacao}",
                    "status=" + response.statusCode(), "token_slot=PREVIOUS", "duration_ms=",
                    "request_id=" + response.header(RequestIdFilter.HEADER));
            assertThat(line).doesNotContain(canary, TestSecrets.ADMIN_TOKEN_PREVIOUS, TestSecrets.ADMIN_TOKEN);
        }
    }

    @Test
    void aRefusedAdminCallIsAccessLoggedWithoutASlot() {
        try (CapturedEvents log = CapturedEvents.of(ManagementAccessLogFilter.class)) {
            management().get(ADMIN + "/x");

            assertThat(log.events()).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                    .contains("status=401", "token_slot=-"));
        }
    }
}
