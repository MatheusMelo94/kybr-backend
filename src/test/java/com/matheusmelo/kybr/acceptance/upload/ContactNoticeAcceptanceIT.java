package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.acceptance.upload.fixtures.EmailRules;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.service.email.EmailMessage;
import com.matheusmelo.kybr.service.email.RecordingEmailSender;
import com.matheusmelo.kybr.service.jobs.JobName;
import com.matheusmelo.kybr.support.TestSecrets;
import com.matheusmelo.kybr.support.jobs.JobTriggers;
import io.restassured.RestAssured;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;

/**
 * The contact-change notice as configured today, with its switch off until its copy is approved: a
 * change queues one notice for the previous address, kept encrypted, which the dispatcher leaves
 * pending until it expires after 30 days. Neither address ever reaches a log or a plain-text field.
 * Also the operator's test e-mail, the one e-mail that can be sent today, against the e-mail rules.
 */
@ExtendWith(OutputCaptureExtension.class)
class ContactNoticeAcceptanceIT extends UploadAcceptanceTest {

    @Autowired
    private JobTriggers jobs;
    @Autowired
    private RecordingEmailSender recorder;
    @LocalManagementPort
    private int managementPort;

    private final String oldAddress = "anterior." + UUID.randomUUID() + "@example.test";
    private final String newAddress = "novo." + UUID.randomUUID() + "@example.test";

    @BeforeEach
    void noRecordedMail() {
        recorder.clear();
    }

    private List<OutboxEvent> outboxOfThisCase() {
        return mongo.find(query(where("case_id").is(pending.linkCase().id())), OutboxEvent.class);
    }

    @Test
    void withTheSwitchOffAChangeQueuesOneNoticeThatStaysPendingAndExpiresInThirtyDays(CapturedOutput output) {
        assertThat(putEmail(workspace.slug(), pending.token(), oldAddress).statusCode()).isBetween(200, 299);
        assertThat(putEmail(workspace.slug(), pending.token(), newAddress).statusCode()).isBetween(200, 299);
        OutboxEvent queued = outboxOfThisCase().getFirst();

        for (int i = 0; i < 5; i++) {
            jobs.run(JobName.DISPATCHER);
        }

        assertThat(outboxOfThisCase()).singleElement().isEqualTo(queued);
        assertThat(queued.pendente()).isTrue();
        assertThat(Duration.between(queued.criadoEm(), queued.expiraEm())).isEqualTo(Duration.ofDays(30));
        assertThat(recorder.recorded()).noneMatch(r -> r.message().to().equalsIgnoreCase(oldAddress));
        assertThat(output.getAll()).doesNotContain(oldAddress).doesNotContain(newAddress)
                .doesNotContain(pending.token());
    }

    @Test
    void neitherAddressIsStoredInPlainText() {
        putEmail(workspace.slug(), pending.token(), oldAddress);
        putEmail(workspace.slug(), pending.token(), newAddress);
        UUID caseId = pending.linkCase().id();

        for (String collection : List.of("cases", "outbox_events", "audit_events")) {
            String field = "cases".equals(collection) ? "_id" : "case_id";
            List<Document> rows = mongo.find(query(where(field).is(caseId)), Document.class, collection);
            assertThat(rows).as(collection).isNotEmpty();
            for (Document stored : rows) {
                assertThat(stored.toJson()).as(collection).doesNotContain(oldAddress).doesNotContain(newAddress);
            }
        }
        assertThat(mongo.getCollection("rate_limit_counters").countDocuments(
                new Document("_id", new Document("$regex", "anterior\\.|novo\\.")))).isZero();
    }

    @Test
    void theOperatorTestEmailFollowsTheEmailRules() {
        int status = RestAssured.given().port(managementPort).header("Authorization", "Bearer " + TestSecrets.ADMIN_TOKEN)
                .contentType("application/json").body(Map.of("email", "operador.teste@example.test"))
                .post("/actuator/admin/email-teste").statusCode();

        assertThat(status).isEqualTo(204);
        EmailMessage message = recorder.recorded().getLast().message();
        EmailRules.assertFollowsTheRules(message, "127.0.0.1", "Mozilla");
    }
}
