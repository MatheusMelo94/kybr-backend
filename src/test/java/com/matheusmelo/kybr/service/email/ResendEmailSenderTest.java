package com.matheusmelo.kybr.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class ResendEmailSenderTest {

    private static final String BASE = "https://provider.example.test";
    private static final String FROM = "Kybr <nao-responda@kybr.com.br>";
    private static final String TO = "vendedor.sintetico@example.com";
    private static final EmailMessage MESSAGE = new EmailMessage(TO, "Assunto", "Texto.", "<p>Texto.</p>");

    /** Generated per run: no key material in the repository. */
    private final String apiKey = "re_" + randomKey();
    private MockRestServiceServer server;
    private RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    @Test
    void sendsBothPartsWithTheIdempotencyKeyAndTheKeyInAHeaderOnly() {
        server.expect(requestTo(BASE + "/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + apiKey))
                .andExpect(header("Idempotency-Key", "evento-1"))
                .andExpect(jsonPath("$.from").value(FROM))
                .andExpect(jsonPath("$.to[0]").value(TO))
                .andExpect(jsonPath("$.subject").value("Assunto"))
                .andExpect(jsonPath("$.text").value("Texto."))
                .andExpect(jsonPath("$.html").value("<p>Texto.</p>"))
                .andRespond(withSuccess("{\"id\":\"synthetic\"}", MediaType.APPLICATION_JSON));

        EmailSendResult result = sender(true).send(MESSAGE, "evento-1");

        server.verify();
        assertThat(result).isEqualTo(new EmailSendResult.Sent());
    }

    @ParameterizedTest
    @ValueSource(ints = {409, 429, 500, 502, 503})
    void rateLimitsInFlightDuplicatesAndServerErrorsAreRetryable(int status) {
        server.expect(requestTo(BASE + "/emails")).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThat(sender(true).send(MESSAGE, "evento-1")).isEqualTo(new EmailSendResult.Retryable("http_" + status));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 422})
    void otherClientErrorsArePermanent(int status) {
        server.expect(requestTo(BASE + "/emails")).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThat(sender(true).send(MESSAGE, "evento-1")).isEqualTo(new EmailSendResult.Permanent("http_" + status));
    }

    @Test
    void aTimeoutIsRetryable() {
        server.expect(requestTo(BASE + "/emails")).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThat(sender(true).send(MESSAGE, "evento-1")).isEqualTo(new EmailSendResult.Retryable("rede"));
    }

    @Test
    void aConnectionFailureIsRetryable() {
        server.expect(requestTo(BASE + "/emails")).andRespond(withException(new IOException("refused")));

        assertThat(sender(true).send(MESSAGE, "evento-1")).isEqualTo(new EmailSendResult.Retryable("rede"));
    }

    @Test
    void anErrorIsLoggedAsTypeAndStatusOnlyNeverTheBodyTheRecipientOrTheKey(CapturedOutput output) {
        server.expect(requestTo(BASE + "/emails")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"Invalid `to` field: " + TO + "\",\"key\":\"" + apiKey + "\"}"));

        sender(true).send(MESSAGE, "evento-1");

        assertThat(output.getAll()).contains("event=email_provider_error").contains("status=422")
                .doesNotContain(TO).doesNotContain(apiKey).doesNotContain("Invalid");
    }

    @Test
    void withTheDomainNotVerifiedNothingIsSentAndTheFailureIsPermanentAndAlerted(CapturedOutput output) {
        server.expect(ExpectedCount.never(), requestTo(BASE + "/emails"));

        EmailSendResult result = sender(false).send(MESSAGE, "evento-1");

        server.verify();
        assertThat(result).isEqualTo(new EmailSendResult.Permanent("dns_nao_verificado"));
        assertThat(output.getAll()).contains("event=email_alert motivo=dns_nao_verificado").doesNotContain(TO);
    }

    @Test
    void theTextFormNeverShowsTheKey() {
        assertThat(sender(true).toString()).doesNotContain(apiKey);
    }

    private ResendEmailSender sender(boolean dnsVerified) {
        return new ResendEmailSender(new ResendEmailSender.Settings(URI.create(BASE), apiKey, FROM, dnsVerified), client);
    }

    private static String randomKey() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
