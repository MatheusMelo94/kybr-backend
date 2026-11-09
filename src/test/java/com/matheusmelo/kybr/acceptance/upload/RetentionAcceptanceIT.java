package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.acceptance.upload.fixtures.SampleFiles;
import com.matheusmelo.kybr.model.document.DocumentRequest;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.EstadoRetencao;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.service.jobs.JobName;
import com.matheusmelo.kybr.support.jobs.JobTriggers;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.upload.ScriptedScanner;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Retention end to end: a case past its retention date loses its stored documents, its contact
 * e-mail and any queued notice, while the case, its audit trail and its seller stay. Its token is
 * revoked first, so from then on the case read, the contact change and the upload all answer exactly
 * like an unknown token, and an upload still in flight keeps nothing.
 */
class RetentionAcceptanceIT extends UploadAcceptanceTest {

    @Autowired
    private JobTriggers jobs;

    private final String firstAddress = "primeiro." + UUID.randomUUID() + "@example.test";
    private final String secondAddress = "segundo." + UUID.randomUUID() + "@example.test";

    private void dueAt(UUID caseId, Instant when) {
        mongo.updateFirst(query(where("_id").is(caseId)), Update.update(LinkCase.RETENCAO_ATE, when), LinkCase.class);
    }

    /** Runs retention until this case is concluded (other due cases in the shared database may come first). */
    private void runRetentionUntilConcluded(UUID caseId) {
        for (int run = 0; run < 10; run++) {
            jobs.run(JobName.RETENTION);
            LinkCase linkCase = stored(caseId);
            if (linkCase.retencao() != null && linkCase.retencao().estado() == EstadoRetencao.CONCLUIDA) {
                return;
            }
        }
        throw new AssertionError("retention never concluded the case: " + stored(caseId).retencao());
    }

    private List<AuditEvent> retentionAudits(UUID caseId) {
        return auditsOf(caseId).stream().filter(a -> a.tipo() == TipoAuditoria.RETENCAO_EXECUTADA).toList();
    }

    private List<OutboxEvent> outboxOf(UUID caseId) {
        return mongo.find(query(where("case_id").is(caseId)), OutboxEvent.class);
    }

    private static Set<String> headerNames(Response response) {
        Set<String> names = new TreeSet<>();
        response.headers().asList().forEach(h -> names.add(h.getName().toLowerCase()));
        return names;
    }

    private static void assertSame(Response actual, Response unknown) {
        assertError(actual, 404, "CASO_NAO_ENCONTRADO");
        assertThat(actual.asString()).isEqualTo(unknown.asString());
        assertThat(headerNames(actual)).isEqualTo(headerNames(unknown));
    }

    private void assertTokenAnswersLikeAnUnknownOneEverywhere(String token) {
        String slug = workspace.slug();
        String unknown = TestCases.newToken();
        assertSame(readCase(slug, token), readCase(slug, unknown));
        assertSame(putEmail(slug, token, "outro@example.test"), putEmail(slug, unknown, "outro@example.test"));
        assertSame(upload(uploadPath(slug, token), TIPO.name(), SampleFiles.photo("depois")),
                upload(uploadPath(slug, unknown), TIPO.name(), SampleFiles.photo("depois")));
    }

    private void contactChangedOnce(TestCases.Built linkCase) {
        assertThat(putEmail(workspace.slug(), linkCase.token(), firstAddress).statusCode()).isBetween(200, 299);
        assertThat(putEmail(workspace.slug(), linkCase.token(), secondAddress).statusCode()).isBetween(200, 299);
    }

    @Test
    void aDueCaseLosesItsDocumentsContactAndNoticeKeepsItsHistoryAndItsTokenStopsWorking() {
        UUID caseId = pending.linkCase().id();
        assertReceived(upload(pending, TIPO, SampleFiles.photo("a"), SampleFiles.photo("b")), TIPO, 2);
        assertReceived(upload(pending, OTHER_TIPO, SampleFiles.pdf("contrato")), OTHER_TIPO, 1);
        contactChangedOnce(pending);
        assertThat(outboxOf(caseId)).hasSize(1);
        List<UUID> earlierAudits = auditsOf(caseId).stream().map(AuditEvent::id).toList();
        assertThat(earlierAudits).isNotEmpty();
        dueAt(caseId, Instant.now().minus(Duration.ofMinutes(1)));

        runRetentionUntilConcluded(caseId);

        assertThat(storage.keys()).isEmpty();
        assertThat(documentsOf(caseId)).isEmpty();
        assertThat(outboxOf(caseId)).isEmpty();
        LinkCase purged = stored(caseId);
        assertThat(purged).as("the case stays").isNotNull();
        assertThat(purged.contato()).isNull();
        assertThat(purged.tokenRevogadoEm()).isNotNull();
        assertThat(retentionAudits(caseId)).singleElement().satisfies(audit -> {
            assertThat(audit.ator()).isEqualTo(Ator.sistema);
            assertThat(audit.resumo().documentos()).isEqualTo(3);
            assertThat(audit.resumo().contatoRemovido()).isTrue();
        });
        assertThat(auditsOf(caseId)).extracting(AuditEvent::id).containsAll(earlierAudits);
        assertTokenAnswersLikeAnUnknownOneEverywhere(pending.token());

        jobs.run(JobName.RETENTION);
        assertThat(retentionAudits(caseId)).as("a later run changes nothing").hasSize(1);
    }

    @Test
    void aCaseNotYetDueIsLeftAlone() {
        UUID caseId = pending.linkCase().id();
        assertReceived(upload(pending, TIPO, SampleFiles.photo("fica")), TIPO, 1);
        dueAt(caseId, Instant.now().plus(Duration.ofMinutes(5)));

        jobs.run(JobName.RETENTION);

        assertThat(documentsOf(caseId)).hasSize(1);
        assertThat(storage.keys()).hasSize(1);
        assertThat(stored(caseId).tokenRevogadoEm()).isNull();
        assertThat(readCase(workspace.slug(), pending.token()).statusCode()).isEqualTo(200);
    }

    @Test
    void openDocumentRequestsAreClosedAndTheirTokensStopWorking() {
        UUID caseId = pending.linkCase().id();
        String token = TestCases.newToken();
        mongo.insert(new DocumentRequest(UUID.randomUUID(), workspace.id(), caseId, TestCases.hash(token),
                List.of(DocumentoTipo.CARTAO_CNPJ), Instant.now(), Instant.now().plus(Duration.ofDays(30)), null, null));
        assertReceived(upload(uploadPath(workspace.slug(), token), "CARTAO_CNPJ", SampleFiles.photo("pedido")),
                DocumentoTipo.CARTAO_CNPJ, 1);
        dueAt(caseId, Instant.now().minusSeconds(1));

        runRetentionUntilConcluded(caseId);

        assertThat(mongo.find(query(where(DocumentRequest.CASE_ID).is(caseId)), DocumentRequest.class))
                .allSatisfy(request -> assertThat(request.encerradoEm()).isNotNull());
        assertError(upload(uploadPath(workspace.slug(), token), "CARTAO_CNPJ", SampleFiles.photo("tarde")), 404,
                "CASO_NAO_ENCONTRADO");
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void aRunThatFailsAfterSomeDeletesCompletesLaterWithOneAuditAndTheTokenRevokedThroughout() {
        UUID caseId = pending.linkCase().id();
        assertReceived(upload(pending, TIPO, SampleFiles.photo("1"), SampleFiles.photo("2"), SampleFiles.photo("3")),
                TIPO, 3);
        for (int run = 0; run < 5; run++) {
            jobs.run(JobName.RETENTION); // other cases due in the shared database go first, so they don't use up the scripted failure
        }
        dueAt(caseId, Instant.now().minusSeconds(1));
        storage.failDeleteOnCall(storage.deletes() + 2);

        jobs.run(JobName.RETENTION);

        LinkCase interrupted = stored(caseId);
        assertThat(interrupted.retencao().estado()).isEqualTo(EstadoRetencao.EM_ANDAMENTO);
        assertThat(interrupted.tokenRevogadoEm()).isNotNull();
        assertThat(retentionAudits(caseId)).isEmpty();
        assertError(readCase(workspace.slug(), pending.token()), 404, "CASO_NAO_ENCONTRADO");
        assertError(upload(pending, TIPO, SampleFiles.photo("4")), 404, "CASO_NAO_ENCONTRADO");

        mongo.updateFirst(query(where("_id").is(caseId)),
                Update.update(LinkCase.RETENCAO_LEASE_UNTIL, Instant.now().minusSeconds(1)), LinkCase.class);
        runRetentionUntilConcluded(caseId);

        assertThat(storage.keys()).isEmpty();
        assertThat(documentsOf(caseId)).isEmpty();
        assertThat(retentionAudits(caseId)).singleElement()
                .satisfies(audit -> assertThat(audit.resumo().documentos()).isEqualTo(3));
    }

    @Test
    void anUploadStillInFlightWhenRetentionStartsKeepsNothing() throws Exception {
        UUID caseId = pending.linkCase().id();
        assertReceived(upload(pending, TIPO, SampleFiles.photo("antes")), TIPO, 1);
        dueAt(caseId, Instant.now().minusSeconds(1));
        ScriptedScanner.Gate gate = storage.holdPuts();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Response> inFlight = pool.submit(() -> multipart(TIPO.name(), SampleFiles.photo("durante"))
                    .post(uploadPath(pending)));
            await().atMost(Duration.ofSeconds(10)).until(() -> gate.waiting() == 1);

            runRetentionUntilConcluded(caseId);
            gate.release();

            assertError(inFlight.get(20, TimeUnit.SECONDS), 404, "CASO_NAO_ENCONTRADO");
        } finally {
            gate.release();
            pool.shutdownNow();
        }
        assertThat(storage.keys()).as("no object outlives the purge").isEmpty();
        assertThat(storage.quarantineKeys()).as("nothing stays in quarantine").isEmpty();
        assertThat(documentsOf(caseId)).isEmpty();
        assertNoTempFileLeft();
    }

    @Test
    void retentionDeletesFilesStillBeingVerifiedAndTheScanJobThenAcceptsNothing() {
        UUID caseId = pending.linkCase().id();
        Response received = multipart(TIPO.name(), SampleFiles.photo("pendente")).post(uploadPath(pending));
        assertReceived(received, TIPO, 1);
        assertThat(storage.quarantineKeys()).hasSize(1);
        dueAt(caseId, Instant.now().minusSeconds(1));

        runRetentionUntilConcluded(caseId);
        scanAll();

        assertThat(storage.quarantineKeys()).isEmpty();
        assertThat(storage.keys()).isEmpty();
        assertThat(documentsOf(caseId)).isEmpty();
    }
}
