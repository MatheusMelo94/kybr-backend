package com.matheusmelo.kybr.service.email;

import com.matheusmelo.kybr.config.KybrProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The live sender ({@code kybr.email.mode=resend}): Resend's REST API over {@link RestClient}, no
 * SDK. One {@code POST /emails} per message with both parts and an {@code Idempotency-Key}, so a
 * retry after a lost answer is delivered at most once. The API key goes in the
 * {@code Authorization} header only.
 *
 * <p>Outcomes: 2xx → sent; 429 or 5xx, a timeout or a connection failure → retryable; any other
 * status → permanent. Until {@code kybr.email.dns-verified} is true it sends nothing: every message
 * fails permanently and an alert event is logged. A failure is logged as its type and status only,
 * never the response body (it can echo the recipient), the recipient or the key.
 */
@Component
@ConditionalOnProperty(prefix = "kybr.email", name = "mode", havingValue = "resend")
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);
    static final String DNS_NOT_VERIFIED = "dns_nao_verificado";

    /** What the sender needs; never prints the key. */
    record Settings(URI baseUrl, String apiKey, String from, boolean dnsVerified) {
        @Override
        public String toString() {
            return "Settings[baseUrl=" + baseUrl + ", from=" + from + ", dnsVerified=" + dnsVerified + "]";
        }
    }

    private final Settings settings;
    private final RestClient client;

    @Autowired
    public ResendEmailSender(KybrProperties properties) {
        this(settings(properties.email()), client(properties.email()));
    }

    ResendEmailSender(Settings settings, RestClient client) {
        this.settings = settings;
        this.client = client;
    }

    @Override
    public EmailSendResult send(EmailMessage message, String idempotencyKey) {
        if (!settings.dnsVerified()) {
            log.error("event=email_alert motivo={}", DNS_NOT_VERIFIED);
            return new EmailSendResult.Permanent(DNS_NOT_VERIFIED);
        }
        Map<String, Object> body = Map.of("from", settings.from(), "to", List.of(message.to()),
                "subject", message.subject(), "text", message.text(), "html", message.html());
        try {
            HttpStatusCode status = client.post()
                    .uri("/emails")
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode(), true);
            return outcome(status);
        } catch (RestClientException e) {
            log.warn("event=email_provider_error status=none error={}", rootType(e));
            return new EmailSendResult.Retryable("rede");
        }
    }

    @Override
    public String toString() {
        return "ResendEmailSender[" + settings + "]";
    }

    private static EmailSendResult outcome(HttpStatusCode status) {
        if (status.is2xxSuccessful()) {
            return new EmailSendResult.Sent();
        }
        String motivo = "http_" + status.value();
        log.warn("event=email_provider_error status={}", status.value());
        if (status.value() == 409 || status.value() == 429 || status.is5xxServerError()) {
            // 409: a request with the same idempotency key is still in flight
            return new EmailSendResult.Retryable(motivo);
        }
        return new EmailSendResult.Permanent(motivo);
    }

    private static String rootType(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private static Settings settings(KybrProperties.Email email) {
        return new Settings(email.baseUrl(), email.apiKey(), email.from(), email.dnsVerified());
    }

    private static RestClient client(KybrProperties.Email email) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(email.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(email.readTimeout());
        return RestClient.builder().baseUrl(email.baseUrl().toString()).requestFactory(factory).build();
    }
}
