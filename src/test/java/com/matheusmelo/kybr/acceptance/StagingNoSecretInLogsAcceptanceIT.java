package com.matheusmelo.kybr.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * No secret reaches the logs, on the staging profile, whose console is ECS JSON with the redacting
 * members customizer: the same canary sweep as {@link NoSecretInLogsAcceptanceIT}.
 * Requests carry a valid {@code Fly-Client-IP}, as they do behind Fly's edge.
 */
@ExtendWith(OutputCaptureExtension.class)
class StagingNoSecretInLogsAcceptanceIT extends StagingAcceptanceTest {

    @Test
    void noSecretReachesTheStructuredLogs(CapturedOutput output) {
        SecretCanaries canaries = new SecretCanaries();

        canaries.sendAll(this::edgeKeyed, this::edgeUnkeyed);

        assertThat(output.getAll()).contains("event=access");
        canaries.assertAbsentFrom(output.getAll(), bffKey());
    }

    /**
     * With the staging/prod console active, every stdout line is one
     * JSON object with {@code @timestamp}, {@code log.level} and {@code message}; request-scoped
     * events carry {@code request_id}; and no Spring web DEBUG/TRACE or Mongo driver command logging
     * appears. Logback is JVM-wide and the last-started context picks the console, so this asserts
     * only when the ECS console is the active one (it is in the full suite order) and aborts
     * otherwise.
     */
    @Test
    void theStructuredConsoleIsOneJsonObjectPerLineWithTheRequestId(CapturedOutput output) {
        List<String> requestIds = List.of(
                edgeKeyed().get("/test/web/ok").header("X-Request-Id"),
                edgeUnkeyed().get("/v1/x").header("X-Request-Id"),
                edgeKeyed().get("/test/qa/throw/stagingJsonLine").header("X-Request-Id"));

        // Only this test's request-scoped lines: other contexts' lifecycle lines can interleave.
        List<String> lines = output.getAll().lines().filter(l -> requestIds.stream().anyMatch(l::contains)).toList();
        Assumptions.assumeTrue(lines.stream().anyMatch(l -> l.startsWith("{\"@timestamp\"")),
                "the ECS console is not the active one in this JVM order");
        assertThat(lines).hasSizeGreaterThanOrEqualTo(4);
        for (String line : lines) {
            assertThat(line).as("one JSON object per line").startsWith("{").endsWith("}");
            JsonPath json = JsonPath.from(line);
            assertThat(json.getString("'@timestamp'")).as(line).isNotBlank();
            assertThat(json.getString("log.level")).as(line).isIn("INFO", "WARN", "ERROR");
            assertThat(json.getString("message")).as(line).isNotNull();
            assertThat(json.getString("request_id")).as(line).isIn(requestIds);
        }
        assertThat(lines).filteredOn(l -> l.contains("event=access")).hasSize(3);
        assertThat(lines).filteredOn(l -> l.contains("unhandled_exception")).singleElement()
                .satisfies(l -> assertThat(JsonPath.from(l).getString("error.stack_trace")).contains("QaThrowingController"));
        assertThat(output.getAll().lines().filter(l -> l.contains("o.s.web") || l.contains("org.springframework.web"))
                .filter(l -> l.contains("DEBUG") || l.contains("TRACE")).toList()).as("no DEBUG or TRACE lines").isEmpty();
        assertThat(output.getAll()).as("no driver command logging").doesNotContain("org.mongodb.driver.protocol.command");
    }

    private RequestSpecification edgeKeyed() {
        return http().header("Fly-Client-IP", "198.51.100.7").header("X-Kybr-Client-IP", "198.51.100.4");
    }

    private RequestSpecification edgeUnkeyed() {
        return httpWithoutKey().header("Fly-Client-IP", "198.51.100.7");
    }
}
