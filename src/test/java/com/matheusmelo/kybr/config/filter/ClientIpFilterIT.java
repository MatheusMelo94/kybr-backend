package com.matheusmelo.kybr.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import com.matheusmelo.kybr.support.web.StagingWebIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The BFF spoof case and the direct-to-API case with {@code KYBR_CLIENT_IP_PLATFORM_HEADER=Fly-Client-IP} (the
 * staging profile's value). Shares the staging context with {@code SecurityHeadersStagingIT}.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClientIpFilterIT extends StagingWebIntegrationTest {

    @Autowired
    private MeterRegistry meters;

    @Test
    void theBffSpoofCaseResolvesTheKeyedHeaderAndIgnoresXForwardedFor() {
        Response response = http()
                .header("X-Kybr-Client-IP", "198.51.100.4")
                .header("X-Forwarded-For", "203.0.113.7")
                .header("Fly-Client-IP", "203.0.113.8")
                .get("/test/trust/client-ip");

        assertThat(response.jsonPath().getString("ip")).isEqualTo("198.51.100.4");
        assertThat(response.jsonPath().getString("source")).isEqualTo("FORWARDED");
    }

    @Test
    void withoutAKeyThePlatformHeaderIsTheDirectIp() {
        Response response = httpWithoutKey()
                .header("X-Kybr-Client-IP", "198.51.100.4")
                .header("Fly-Client-IP", "198.51.100.7")
                .get("/livez");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void theDirectCaseResolvesThePlatformIpOnARejectedRoute() {
        try (CapturedEvents captured = CapturedEvents.of(BffKeyFilter.class)) {
            Response response = httpWithoutKey()
                    .header("Fly-Client-IP", "198.51.100.7")
                    .header("X-Forwarded-For", "203.0.113.7")
                    .header("X-Kybr-Client-IP", "203.0.113.9")
                    .get("/v1/anything");

            assertThat(response.statusCode()).isEqualTo(403);
            ILoggingEvent rejection = captured.events().stream()
                    .filter(e -> e.getFormattedMessage().contains("event=bff_key_rejected"))
                    .reduce((a, b) -> b).orElseThrow();
            assertThat(rejection.getMDCPropertyMap())
                    .containsEntry(ClientIpFilter.MDC_IP, "198.51.100.7")
                    .containsEntry(ClientIpFilter.MDC_SOURCE, "DIRECT");
        }
    }

    @Test
    void aValidKeyWithoutTheHeaderIsWarnedAsAbsent(CapturedOutput output) {
        Response response = http().header("Fly-Client-IP", "198.51.100.7").get("/test/trust/client-ip");

        assertThat(response.jsonPath().getString("ip")).isEqualTo("198.51.100.7");
        assertThat(response.jsonPath().getString("source")).isEqualTo("FORWARDED_ABSENT");
        assertThat(output).contains("event=client_ip_absent");
    }

    @Test
    void theLinkInfoRouteExpectsNoClientIpWhileTheLookupRouteDoes() {
        double expected = absent("true");
        double notExpected = absent("false");

        try (CapturedEvents captured = CapturedEvents.of(ClientIpFilter.class)) {
            http().header("Fly-Client-IP", "198.51.100.7").get("/public/v/loja-exemplo");

            assertThat(captured.events()).noneMatch(e -> e.getFormattedMessage().contains("event=client_ip_absent")
                    && e.getLevel() == Level.WARN);
            assertThat(absent("false")).isEqualTo(notExpected + 1);
            assertThat(absent("true")).isEqualTo(expected);

            http().header("Fly-Client-IP", "198.51.100.7").get("/public/v/loja-exemplo/lookup");

            assertThat(captured.events()).anyMatch(e -> e.getFormattedMessage().equals("event=client_ip_absent")
                    && e.getLevel() == Level.WARN);
            assertThat(absent("true")).isEqualTo(expected + 1);
        }
    }

    @Test
    void aMissingPlatformHeaderIsPlatformAbsent(CapturedOutput output) {
        httpWithoutKey().get("/v1/anything");

        assertThat(output).contains("event=client_ip_platform_absent reason=missing");
    }

    private double absent(String esperado) {
        Counter counter = meters.find("kybr.client_ip.absent").tags("esperado", esperado).counter();
        return counter == null ? 0 : counter.count();
    }
}
