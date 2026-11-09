package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.acceptance.upload.fixtures.EmailRules;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.ResultadoEnvio;
import com.matheusmelo.kybr.service.email.EmailMessage;
import com.matheusmelo.kybr.service.email.RecordingEmailSender;
import com.matheusmelo.kybr.service.jobs.JobName;
import com.matheusmelo.kybr.support.jobs.JobTriggers;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.util.EmailMasker;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * The contact-change notice with its switch on (and the test-only copy): a change sends exactly one
 * e-mail, to the previous address only, keyed by the queued event so a retry can't send twice; it
 * follows the e-mail rules and shows the new address masked only. The per-address caps drop the
 * fourth e-mail to one address within an hour, whatever its letter case. Its own context, closed
 * afterwards.
 */
@TestPropertySource(properties = "kybr.email.events.contato-alterado.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ContactNoticeSwitchedOnAcceptanceIT extends UploadAcceptanceTest {

    @Autowired
    private JobTriggers jobs;
    @Autowired
    private RecordingEmailSender recorder;

    private final String oldAddress = "anterior." + UUID.randomUUID() + "@example.test";
    private final String newAddress = "novo." + UUID.randomUUID() + "@example.test";

    @BeforeEach
    void noRecordedMail() {
        recorder.clear();
    }

    private List<RecordingEmailSender.Recorded> sentTo(String address) {
        return recorder.recorded().stream().filter(r -> r.message().to().equalsIgnoreCase(address)).toList();
    }

    private void change(TestCases.Built linkCase, String from, String to) {
        assertThat(putEmail(workspace.slug(), linkCase.token(), from).statusCode()).isBetween(200, 299);
        assertThat(putEmail(workspace.slug(), linkCase.token(), to).statusCode()).isBetween(200, 299);
    }

    private List<OutboxEvent> outboxOf(UUID caseId) {
        return mongo.find(query(where("case_id").is(caseId)), OutboxEvent.class);
    }

    @Test
    void aChangeSendsExactlyOneNoticeToThePreviousAddressOnly(CapturedOutput output) {
        change(pending, oldAddress, newAddress);
        OutboxEvent queued = outboxOf(pending.linkCase().id()).getFirst();

        jobs.run(JobName.DISPATCHER);
        jobs.run(JobName.DISPATCHER);
        jobs.run(JobName.DISPATCHER);

        assertThat(sentTo(oldAddress)).singleElement()
                .satisfies(sent -> assertThat(sent.idempotencyKey()).isEqualTo(queued.id().toString()));
        assertThat(sentTo(newAddress)).isEmpty();
        OutboxEvent processed = outboxOf(pending.linkCase().id()).getFirst();
        assertThat(processed.pendente()).isFalse();
        assertThat(processed.resultadoEnvio()).isEqualTo(ResultadoEnvio.enviado);
        assertThat(output.getAll()).doesNotContain(oldAddress).doesNotContain(newAddress)
                .doesNotContain(pending.token());
    }

    @Test
    void theNoticeFollowsTheEmailRulesAndShowsTheNewAddressMaskedOnly() {
        change(pending, oldAddress, newAddress);

        jobs.run(JobName.DISPATCHER);

        EmailMessage message = sentTo(oldAddress).getFirst().message();
        EmailRules.assertFollowsTheRules(message, newAddress, pending.token(), "127.0.0.1");
        assertThat(message.text()).contains(EmailMasker.mask(newAddress).replace("@example.test", "@example[.]test"));
        assertThat(message.subject()).doesNotContain(pending.token()).doesNotContain("@");
    }

    @Test
    void theFourthNoticeToOneAddressWithinAnHourIsDroppedWhateverItsCase() {
        String shared = "partilhado." + UUID.randomUUID() + "@example.test";
        List<UUID> caseIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            TestCases.Built linkCase = i == 0 ? pending : seedCase(EstadoCaso.pending);
            String from = i == 3 ? shared.toUpperCase(Locale.ROOT) : shared;
            change(linkCase, from, "novo-" + i + "." + UUID.randomUUID() + "@example.test");
            caseIds.add(linkCase.linkCase().id());
        }

        jobs.run(JobName.DISPATCHER);

        assertThat(sentTo(shared)).hasSize(3);
        List<ResultadoEnvio> outcomes = caseIds.stream().map(id -> outboxOf(id).getFirst())
                .peek(event -> assertThat(event.pendente()).isFalse()).map(OutboxEvent::resultadoEnvio).toList();
        assertThat(outcomes).containsExactlyInAnyOrder(ResultadoEnvio.enviado, ResultadoEnvio.enviado,
                ResultadoEnvio.enviado, ResultadoEnvio.limitado);
        assertThat(mongo.find(query(where("_id").regex("partilhado")), Document.class, "rate_limit_counters"))
                .as("the counters never hold the address").isEmpty();
    }
}
