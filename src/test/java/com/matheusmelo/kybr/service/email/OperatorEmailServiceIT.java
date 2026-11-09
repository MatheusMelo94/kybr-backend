package com.matheusmelo.kybr.service.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.link.TokenSlot;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.RateLimitCounterRepository;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.service.ratelimit.RateLimiter;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.email.ScriptedEmailSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OperatorEmailServiceIT extends MongoIntegrationTest {

    @Autowired
    private RateLimitCounterRepository counters;
    @Autowired
    private KeyHasher keyHasher;
    @Autowired
    private AuditEventRepository audits;

    private final ScriptedEmailSender sender = new ScriptedEmailSender();
    private final String allowed = "operador." + UUID.randomUUID() + "@example.test";
    private OperatorEmailService service;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TemplateRenderer renderer = new TemplateRenderer();
        EmailService emails = new EmailService(renderer,
                new RateLimiter(counters, keyHasher, Clock.systemUTC(), meters), sender,
                new KybrProperties.Email.Caps(3, 10), meters);
        service = new OperatorEmailService(emails, audits, List.of(allowed), Clock.systemUTC());
    }

    @Test
    void sendsTheFixedCopyToAnAllowedAddressWithAFreshIdempotencyKey() {
        assertThat(service.sendTest(allowed.toUpperCase(), TokenSlot.PREVIOUS))
                .isEqualTo(OperatorEmailService.Result.SENT);
        assertThat(service.sendTest(allowed, TokenSlot.CURRENT)).isEqualTo(OperatorEmailService.Result.SENT);

        assertThat(sender.calls()).hasSize(2);
        assertThat(sender.calls().get(0).idempotencyKey()).isNotEqualTo(sender.calls().get(1).idempotencyKey());
        assertThat(sender.calls().get(0).message().to()).isEqualTo(allowed);
    }

    @Test
    void anAddressNotOnTheListIsRefusedWithoutSendingOrAuditing() {
        long before = audits.count();

        assertThat(service.sendTest("outro@example.test", TokenSlot.CURRENT))
                .isEqualTo(OperatorEmailService.Result.NOT_ALLOWED);
        assertThat(service.sendTest(null, TokenSlot.CURRENT)).isEqualTo(OperatorEmailService.Result.NOT_ALLOWED);

        assertThat(sender.calls()).isEmpty();
        assertThat(audits.count()).isEqualTo(before);
    }

    @Test
    void aProviderFailureIsReportedAsNotSentAndNotAudited() {
        sender.permanentOnCall(1);
        long before = audits.findAll().stream().filter(e -> e.tipo() == TipoAuditoria.EMAIL_TESTE_ENVIADO).count();

        assertThat(service.sendTest(allowed, TokenSlot.CURRENT)).isEqualTo(OperatorEmailService.Result.NOT_SENT);

        assertThat(audits.findAll().stream().filter(e -> e.tipo() == TipoAuditoria.EMAIL_TESTE_ENVIADO).count())
                .isEqualTo(before);
    }

    @Test
    void theTestEmailCarriesTheSameWordsInBothParts() {
        service.sendTest(allowed, TokenSlot.CURRENT);

        EmailMessage message = sender.calls().getFirst().message();
        assertThat(TemplateRendererTest.normalise(TemplateRendererTest.visibleText(message.html())))
                .isEqualTo(TemplateRendererTest.normalise(message.text()));
        assertThat(message.text()).matches("(?s).*\\d{2}/\\d{2}/\\d{4} às \\d{2}:\\d{2}.*");
    }
}
