package com.matheusmelo.kybr.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.RateLimitCounter;
import com.matheusmelo.kybr.repository.RateLimitCounterRepository;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.service.ratelimit.RateLimiter;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.MovableClock;
import com.matheusmelo.kybr.support.email.ScriptedEmailSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EmailServiceIT extends MongoIntegrationTest {

    private static final String TEMPLATE = "aviso";

    @Autowired
    private RateLimitCounterRepository counters;
    @Autowired
    private KeyHasher keyHasher;
    @Autowired
    private MongoTemplate mongo;

    private final ScriptedEmailSender sender = new ScriptedEmailSender();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MovableClock clock = new MovableClock(Instant.now().truncatedTo(ChronoUnit.DAYS).plusSeconds(60));
    private EmailService emails;

    @BeforeEach
    void setUp() {
        RateLimiter limiter = new RateLimiter(counters, keyHasher, clock, meters);
        emails = new EmailService(new TemplateRenderer("classpath*:email-fixtures/valid/*.txt"), limiter, sender,
                new KybrProperties.Email.Caps(3, 10), meters);
    }

    @Test
    void sendsTheRenderedMessageWithTheIdempotencyKey() {
        String to = address();

        EmailOutcome outcome = emails.send(TEMPLATE, values(), to, "evento-1");

        assertThat(outcome).isEqualTo(new EmailOutcome.Sent());
        assertThat(sender.calls()).singleElement().satisfies(call -> {
            assertThat(call.idempotencyKey()).isEqualTo("evento-1");
            assertThat(call.message().to()).isEqualTo(to);
            assertThat(call.message().subject()).isEqualTo("Cadastro de Empresa Exemplo LTDA atualizado");
            assertThat(call.message().html()).contains("<p>");
        });
        assertThat(meters.counter("kybr.email.sent", "tipo", TEMPLATE).count()).isEqualTo(1);
    }

    @Test
    void theFourthEmailToOneAddressWithinAnHourIsDroppedAndCounted() {
        String to = address();
        for (int i = 1; i <= 3; i++) {
            assertThat(emails.send(TEMPLATE, values(), to, "k" + i)).isEqualTo(new EmailOutcome.Sent());
        }

        assertThat(emails.send(TEMPLATE, values(), to, "k4")).isEqualTo(new EmailOutcome.Capped());

        assertThat(sender.calls()).hasSize(3);
        assertThat(meters.counter("kybr.email.capped", "tipo", TEMPLATE).count()).isEqualTo(1);
    }

    @Test
    void failedSendsDontUseUpTheAddressCap() {
        String to = address();
        sender.retryableOnCall(1);
        sender.permanentOnCall(2);
        emails.send(TEMPLATE, values(), to, "k1");
        emails.send(TEMPLATE, values(), to, "k2");

        for (int i = 3; i <= 5; i++) {
            assertThat(emails.send(TEMPLATE, values(), to, "k" + i)).isEqualTo(new EmailOutcome.Sent());
        }
        assertThat(emails.send(TEMPLATE, values(), to, "k6")).isEqualTo(new EmailOutcome.Capped());
    }

    @Test
    void theAddressCaseNeverSplitsTheCap() {
        String local = "Vendedor." + UUID.randomUUID();
        emails.send(TEMPLATE, values(), local + "@Example.TEST", "k1");
        emails.send(TEMPLATE, values(), local.toLowerCase() + "@example.test", "k2");
        emails.send(TEMPLATE, values(), local.toUpperCase() + "@EXAMPLE.TEST", "k3");

        assertThat(emails.send(TEMPLATE, values(), local + "@example.test", "k4")).isEqualTo(new EmailOutcome.Capped());
    }

    @Test
    void theEleventhEmailToOneAddressInADayIsDropped() {
        String to = address();
        int sent = 0;
        while (sent < 10) {
            for (int i = 0; i < 3 && sent < 10; i++, sent++) {
                assertThat(emails.send(TEMPLATE, values(), to, "k" + sent)).isEqualTo(new EmailOutcome.Sent());
            }
            clock.advance(Duration.ofHours(1));
        }

        assertThat(emails.send(TEMPLATE, values(), to, "k11")).isEqualTo(new EmailOutcome.Capped());
        assertThat(sender.calls()).hasSize(10);
    }

    @Test
    void theStoredCounterKeyIsAnHmacNeverTheAddress() {
        String to = address();

        emails.send(TEMPLATE, values(), to, "k1");

        String local = to.substring(0, to.indexOf('@'));
        assertThat(mongo.getCollection(RateLimitCounter.COLLECTION).find())
                .extracting(Document::toJson)
                .noneMatch(json -> json.contains(local) || json.contains(to));
    }

    @Test
    void providerOutcomesPassThrough() {
        sender.retryableOnCall(1);
        sender.permanentOnCall(2);

        assertThat(emails.send(TEMPLATE, values(), address(), "k1")).isEqualTo(new EmailOutcome.Retryable("scripted"));
        assertThat(emails.send(TEMPLATE, values(), address(), "k2")).isEqualTo(new EmailOutcome.Permanent("scripted"));
    }

    @Test
    void neverSendsInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> emails.send(TEMPLATE, values(), address(), "k1"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(sender.calls()).isEmpty();
    }

    private static String address() {
        return "vendedor." + UUID.randomUUID() + "@example.test";
    }

    private static Map<String, TemplateValue> values() {
        return Map.of(
                "responsavel", new TemplateValue.TextoVendedor("Maria"),
                "razao_social", new TemplateValue.RazaoSocial("Empresa Exemplo LTDA"),
                "quando", new TemplateValue.DataHora(Instant.parse("2026-11-07T17:30:00Z")),
                "link", new TemplateValue.Link(URI.create("https://kybr.com.br/v/loja/c/sintetico")));
    }
}
