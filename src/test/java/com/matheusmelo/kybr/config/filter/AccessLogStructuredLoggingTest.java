package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.support.ProfileBoot;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The access line through staging's ECS JSON writer: the request context fields already in the MDC
 * (request id, client IP and its source) must not be repeated as key-value pairs, or the writer
 * rejects the event ("Duplicate nested pairs") and the line is lost.
 */
@ExtendWith(OutputCaptureExtension.class)
class AccessLogStructuredLoggingTest {

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private ConfigurableApplicationContext context;

    @AfterEach
    void cleanUp() {
        MDC.clear();
        if (context != null) {
            context.close();
        }
    }

    @Test
    void anAccessLineWithTheRequestContextInTheMdcIsOneValidEcsJsonLine(CapturedOutput output) {
        context = ProfileBoot.start("staging", ProfileBoot.validCloudEnv().toArray(String[]::new));
        LoggerContext logback = (LoggerContext) LoggerFactory.getILoggerFactory();
        long before = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/livez");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, requestId);
        request.setAttribute(ClientIp.ATTRIBUTE, new ClientIp("203.0.113.7", ClientIp.Source.DIRECT));
        MDC.put(RequestIdFilter.MDC_KEY, requestId);
        MDC.put(ClientIpFilter.MDC_IP, "203.0.113.7");
        MDC.put(ClientIpFilter.MDC_SOURCE, ClientIp.Source.DIRECT.name());
        AccessLogFilter.write(request, 200, new AccessLogFilter.Pending(System.nanoTime(), "GET", "/livez", "[]"));

        List<String> lines = output.getAll().lines()
                .filter(line -> line.startsWith("{") && line.contains("event=access")).toList();
        assertThat(lines).as("one ECS JSON access line").hasSize(1);
        JsonNode event = STRICT.readTree(lines.getFirst());
        assertThat(event.path("request_id").asString()).isEqualTo(requestId);
        assertThat(event.path("client_ip").asString()).isEqualTo("203.0.113.7");
        assertThat(event.path("client_ip_source").asString()).isEqualTo("DIRECT");
        assertThat(event.path("status").asInt()).isEqualTo(200);
        assertThat(lines.getFirst().split("\"request_id\"", -1)).as("request_id written once").hasSize(2);
        assertThat(logback.getStatusManager().getCopyOfStatusList())
                .filteredOn(status -> status.getLevel() == Status.ERROR && status.getTimestamp() >= before)
                .as("no logback error status")
                .isEmpty();
    }
}
