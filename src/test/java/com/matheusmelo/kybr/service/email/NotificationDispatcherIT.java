package com.matheusmelo.kybr.service.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.Contato;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.ResultadoEnvio;
import com.matheusmelo.kybr.model.link.TipoOutbox;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.OutboxClaims;
import com.matheusmelo.kybr.repository.RateLimitCounterRepository;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.service.crypto.FieldCipher;
import com.matheusmelo.kybr.service.jobs.JobName;
import com.matheusmelo.kybr.service.ratelimit.RateLimiter;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.MovableClock;
import com.matheusmelo.kybr.support.email.ScriptedEmailSender;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.util.EmailMasker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(OutputCaptureExtension.class)
class NotificationDispatcherIT extends MongoIntegrationTest {

    private static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofHours(1));

    @Autowired
    private OutboxClaims claims;
    @Autowired
    private LinkCaseRepository cases;
    @Autowired
    private FieldCipher cipher;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private RateLimitCounterRepository counters;
    @Autowired
    private KeyHasher keyHasher;

    private final ScriptedEmailSender sender = new ScriptedEmailSender();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MovableClock clock = new MovableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    private final TemplateRenderer renderer = new TemplateRenderer();
    private final String oldAddress = "antigo." + UUID.randomUUID() + "@example.test";
    private final String newAddress = "novo." + UUID.randomUUID() + "@example.test";
    private OutboxEvent event;

    @BeforeEach
    void setUp() {
        mongo.remove(new Query(), OutboxEvent.class);
        UUID caseId = UUID.randomUUID();
        Instant now = clock.instant();
        cases.save(TestCases.builder(TestWorkspaces.builder().build()).id(caseId)
                .contato(new Contato(cipher.encrypt(newAddress, caseId), EmailMasker.mask(newAddress), now))
                .build().linkCase());
        UUID eventId = UUID.randomUUID();
        event = mongo.insert(new OutboxEvent(eventId, TipoOutbox.CONTATO_ALTERADO, caseId,
                cipher.encrypt(oldAddress, eventId), now, now.plus(Duration.ofDays(30))));
    }

    @Test
    void isTheDispatcherJob() {
        assertThat(dispatcher(false).name()).isEqualTo(JobName.DISPATCHER);
    }

    @Test
    void withTheSwitchOffEventsAreLeftUntouched() {
        NotificationDispatcher dispatcher = dispatcher(false);
        for (int i = 0; i < 5; i++) {
            dispatcher.runOnce();
        }

        assertThat(sender.calls()).isEmpty();
        assertThat(stored()).isEqualTo(event);
    }

    @Test
    void withTheSwitchOnTheOldAddressGetsExactlyOneEmailKeyedByTheEventId() {
        NotificationDispatcher dispatcher = dispatcher(true);

        dispatcher.runOnce();
        dispatcher.runOnce();

        assertThat(sender.calls()).singleElement().satisfies(call -> {
            assertThat(call.message().to()).isEqualTo(oldAddress);
            assertThat(call.idempotencyKey()).isEqualTo(event.id().toString());
        });
        OutboxEvent stored = stored();
        assertThat(stored.pendente()).isFalse();
        assertThat(stored.processadoEm()).isEqualTo(clock.instant());
        assertThat(stored.resultadoEnvio()).isEqualTo(ResultadoEnvio.enviado);
    }

    @Test
    void theNoticeShowsTheNewAddressMaskedOnly() {
        dispatcher(true).runOnce();

        EmailMessage message = sender.calls().getFirst().message();
        assertThat(message.text()).contains(EmailMasker.mask(newAddress).replace("@example.test", "@example[.]test"));
        assertThat(message.subject() + message.text() + message.html()).doesNotContain(newAddress);
    }

    @Test
    void theMaskedAddressDomainCantBeTurnedIntoALink() {
        String crafted = "golpe@login-kybr-seguro.com.br";
        cases.save(TestCases.builder(TestWorkspaces.builder().build()).id(event.caseId())
                .contato(new Contato(cipher.encrypt(crafted, event.caseId()), EmailMasker.mask(crafted), clock.instant()))
                .build().linkCase());

        dispatcher(true).runOnce();

        EmailMessage message = sender.calls().getFirst().message();
        assertThat(message.text()).contains("@login-kybr-seguro[.]com[.]br");
        assertThat(message.text() + message.html()).doesNotContain("login-kybr-seguro.com");
    }

    @Test
    void theOldAddressNeverAppearsInLogs(CapturedOutput output) {
        sender.permanentOnCall(1);

        dispatcher(true).runOnce();

        assertThat(output.getAll()).doesNotContain(oldAddress).doesNotContain(newAddress);
    }

    @Test
    void aRetryableFailureBacksOffOneMinuteTenMinutesAndAnHourThenFails() {
        sender.alwaysRetryable();
        NotificationDispatcher dispatcher = dispatcher(true);

        for (Duration wait : BACKOFF) {
            Instant attempt = clock.instant();
            dispatcher.runOnce();
            assertThat(stored().proximaTentativa()).isEqualTo(attempt.plus(wait));
            clock.advance(wait.minusSeconds(1));
            dispatcher.runOnce();
            clock.advance(Duration.ofSeconds(1));
        }
        dispatcher.runOnce();

        assertThat(sender.calls()).hasSize(4);
        OutboxEvent stored = stored();
        assertThat(stored.falhouEm()).isEqualTo(clock.instant());
        assertThat(stored.pendente()).isFalse();
        assertThat(meters.counter("kybr.email.failed", "tipo", "contato-alterado", "motivo", "temporario").count())
                .isEqualTo(1);
    }

    @Test
    void aSendThatThrowsIsRetriedOnTheBackoffAndThenFailsInsteadOfStayingLeased() {
        EmailService throwing = new EmailService(renderer, new RateLimiter(counters, keyHasher, clock, meters),
                (message, key) -> {
                    throw new IllegalStateException("provider client broke");
                }, new KybrProperties.Email.Caps(3, 10), meters);
        NotificationDispatcher dispatcher = dispatcher(true, throwing);

        for (Duration wait : BACKOFF) {
            Instant attempt = clock.instant();
            dispatcher.runOnce();
            assertThat(stored().proximaTentativa()).isEqualTo(attempt.plus(wait));
            assertThat(stored().leaseOwner()).isNull();
            clock.advance(wait);
        }
        dispatcher.runOnce();

        assertThat(stored().falhouEm()).isEqualTo(clock.instant());
        assertThat(meters.counter("kybr.email.failed", "tipo", "contato-alterado", "motivo", "temporario").count())
                .isEqualTo(1);
    }

    @Test
    void anEventClaimedPastTheAttemptLimitFailsWithoutSending() {
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(event.id())),
                new org.springframework.data.mongodb.core.query.Update().set(OutboxEvent.TENTATIVAS, BACKOFF.size() + 1),
                OutboxEvent.class);

        dispatcher(true).runOnce();

        assertThat(sender.calls()).isEmpty();
        assertThat(stored().falhouEm()).isEqualTo(clock.instant());
        assertThat(meters.counter("kybr.email.failed", "tipo", "contato-alterado", "motivo", "temporario").count())
                .isEqualTo(1);
    }

    @Test
    void aSuccessOnTheSecondAttemptSendsOnceAndIsProcessed() {
        sender.retryableOnCall(1);
        NotificationDispatcher dispatcher = dispatcher(true);

        dispatcher.runOnce();
        clock.advance(Duration.ofMinutes(1));
        dispatcher.runOnce();

        assertThat(sender.calls()).hasSize(2);
        assertThat(stored().resultadoEnvio()).isEqualTo(ResultadoEnvio.enviado);
    }

    @Test
    void aPermanentFailureFailsAtOnceWithoutRetry() {
        sender.permanentOnCall(1);
        NotificationDispatcher dispatcher = dispatcher(true);

        dispatcher.runOnce();
        clock.advance(Duration.ofDays(1));
        dispatcher.runOnce();

        assertThat(sender.calls()).hasSize(1);
        assertThat(stored().falhouEm()).isNotNull();
        assertThat(meters.counter("kybr.email.failed", "tipo", "contato-alterado", "motivo", "permanente").count())
                .isEqualTo(1);
    }

    @Test
    void aCappedMessageIsProcessedAsLimitedAndNeverRetried() {
        EmailService oneAllowed = emailService(new KybrProperties.Email.Caps(1, 1));
        oneAllowed.send("contato-alterado", java.util.Map.of("email_novo", new TemplateValue.Texto("x•••@example.test"),
                "quando", new TemplateValue.DataHora(clock.instant())), oldAddress, "anterior");
        NotificationDispatcher dispatcher = dispatcher(true, oneAllowed);

        dispatcher.runOnce();
        clock.advance(Duration.ofDays(2));
        dispatcher.runOnce();

        assertThat(sender.calls()).hasSize(1);
        OutboxEvent stored = stored();
        assertThat(stored.resultadoEnvio()).isEqualTo(ResultadoEnvio.limitado);
        assertThat(stored.processadoEm()).isNotNull();
        assertThat(meters.counter("kybr.email.capped", "tipo", "contato-alterado").count()).isEqualTo(1);
    }

    @Test
    void twoDispatchersSendExactlyOnce() throws Exception {
        NotificationDispatcher first = dispatcher(true);
        NotificationDispatcher second = dispatcher(true);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> a = pool.submit(() -> {
                start.await();
                first.runOnce();
                return null;
            });
            Future<?> b = pool.submit(() -> {
                start.await();
                second.runOnce();
                return null;
            });
            start.countDown();
            a.get();
            b.get();
        }

        assertThat(sender.calls()).hasSize(1);
    }

    @Test
    void aCaseWithoutAContactFailsTheEventWithoutSending() {
        cases.deleteById(event.caseId());

        dispatcher(true).runOnce();

        assertThat(sender.calls()).isEmpty();
        assertThat(stored().falhouEm()).isNotNull();
    }

    private NotificationDispatcher dispatcher(boolean enabled) {
        return dispatcher(enabled, emailService(new KybrProperties.Email.Caps(3, 10)));
    }

    private NotificationDispatcher dispatcher(boolean enabled, EmailService emails) {
        return new NotificationDispatcher(claims, cases, cipher, emails, renderer,
                new NotificationDispatcher.Settings(enabled, Duration.ofSeconds(60), BACKOFF), clock, meters);
    }

    private EmailService emailService(KybrProperties.Email.Caps caps) {
        return new EmailService(renderer, new RateLimiter(counters, keyHasher, clock, meters), sender, caps, meters);
    }

    private OutboxEvent stored() {
        return mongo.findById(event.id(), OutboxEvent.class);
    }
}
