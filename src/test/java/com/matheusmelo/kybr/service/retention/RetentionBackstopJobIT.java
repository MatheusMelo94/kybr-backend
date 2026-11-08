package com.matheusmelo.kybr.service.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.Contato;
import com.matheusmelo.kybr.model.link.EstadoRetencao;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OrigemRetencao;
import com.matheusmelo.kybr.model.link.Retencao;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.DocumentRepository;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.OutboxEventRepository;
import com.matheusmelo.kybr.repository.RetentionClaims;
import com.matheusmelo.kybr.service.jobs.JobName;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.MovableClock;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.upload.ScriptedStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;

class RetentionBackstopJobIT extends MongoIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-07T15:00:00Z");
    private static final Period BACKSTOP = Period.ofMonths(12);

    @Autowired
    private RetentionClaims claims;
    @Autowired
    private LinkCaseRepository cases;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private AuditEventRepository audits;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MongoTemplate mongo;

    private final MovableClock clock = new MovableClock(NOW);
    private final Workspace workspace = TestWorkspaces.builder().build();

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), LinkCase.class);
        mongo.remove(new Query(), Document.class);
    }

    @Test
    void isTheBackstopJob() {
        assertThat(job().name()).isEqualTo(JobName.BACKSTOP);
    }

    @Test
    void aCaseIdleFor19MonthsGetsAPastDateAndIsThenPurged() {
        Instant created = months(-19);
        LinkCase idle = save(TestCases.builder(workspace).criadoEm(created));

        job().runOnce();

        LinkCase stored = cases.findById(idle.id()).orElseThrow();
        assertThat(stored.retencaoAte()).isEqualTo(plusMonths(created, 12));
        assertThat(stored.retencaoAte()).isBefore(NOW);
        assertThat(stored.retencaoOrigem()).isEqualTo(OrigemRetencao.BACKSTOP);

        new RetentionJob(claims, documents, outbox, audits, new ScriptedStorage(clock), transactionManager,
                Duration.ofMinutes(5), clock, new SimpleMeterRegistry()).runOnce();

        assertThat(cases.findById(idle.id()).orElseThrow().retencao().estado()).isEqualTo(EstadoRetencao.CONCLUIDA);
    }

    @Test
    void anUploadTwoMonthsAgoGivesAFutureDate() {
        LinkCase linkCase = save(TestCases.builder(workspace).criadoEm(months(-19)));
        upload(linkCase, months(-2));

        job().runOnce();

        assertThat(cases.findById(linkCase.id()).orElseThrow().retencaoAte()).isEqualTo(plusMonths(months(-2), 12))
                .isAfter(NOW);
    }

    @Test
    void aContactChangeCountsAsActivity() {
        LinkCase linkCase = save(TestCases.builder(workspace).criadoEm(months(-19))
                .contato(new Contato(new byte[] {1}, "m•••@example.test", months(-5))));

        job().runOnce();

        assertThat(cases.findById(linkCase.id()).orElseThrow().retencaoAte()).isEqualTo(plusMonths(months(-5), 12));
    }

    @Test
    void laterActivityMovesABackstopDateForward() {
        LinkCase linkCase = save(TestCases.builder(workspace).criadoEm(months(-6)));
        job().runOnce();
        assertThat(cases.findById(linkCase.id()).orElseThrow().retencaoAte()).isEqualTo(plusMonths(months(-6), 12));

        clock.set(months(3));
        upload(linkCase, months(3));
        job().runOnce();

        assertThat(cases.findById(linkCase.id()).orElseThrow().retencaoAte()).isEqualTo(plusMonths(months(3), 12));
    }

    @Test
    void aDecisionDateIsNeverTouched() {
        Instant decided = months(-1).plus(Duration.ofDays(365));
        LinkCase linkCase = save(TestCases.builder(workspace).criadoEm(months(-19)).retencaoAte(decided)
                .retencaoOrigem(OrigemRetencao.DECISAO));
        upload(linkCase, months(-1));

        job().runOnce();

        assertThat(cases.findById(linkCase.id()).orElseThrow()).isEqualTo(linkCase);
    }

    @Test
    void aCaseAlreadyClaimedOrPurgedIsNeverTouched() {
        LinkCase claimed = save(TestCases.builder(workspace).criadoEm(months(-19)).retencaoAte(months(-7))
                .retencaoOrigem(OrigemRetencao.BACKSTOP)
                .retencao(new Retencao(EstadoRetencao.EM_ANDAMENTO, "dono", NOW.plusSeconds(60), null)));
        LinkCase purged = save(TestCases.builder(workspace).criadoEm(months(-19)).retencaoAte(months(-7))
                .retencaoOrigem(OrigemRetencao.BACKSTOP)
                .retencao(new Retencao(EstadoRetencao.CONCLUIDA, null, null, months(-7))));
        upload(claimed, months(-1));
        upload(purged, months(-1));

        job().runOnce();

        assertThat(cases.findById(claimed.id()).orElseThrow()).isEqualTo(claimed);
        assertThat(cases.findById(purged.id()).orElseThrow()).isEqualTo(purged);
    }

    @Test
    void aMonthEndClampsToTheLastDayOfTheTargetMonth() {
        Instant leapDay = Instant.parse("2024-02-29T12:00:00Z");
        LinkCase linkCase = save(TestCases.builder(workspace).criadoEm(leapDay));

        job().runOnce();

        assertThat(cases.findById(linkCase.id()).orElseThrow().retencaoAte()).isEqualTo(Instant.parse("2025-02-28T12:00:00Z"));
    }

    private RetentionBackstopJob job() {
        return new RetentionBackstopJob(claims, BACKSTOP);
    }

    private LinkCase save(TestCases.Builder builder) {
        return cases.save(builder.build().linkCase());
    }

    private void upload(LinkCase linkCase, Instant at) {
        UUID id = UUID.randomUUID();
        documents.save(new Document(id, linkCase.workspaceId(), linkCase.id(), null, DocumentoTipo.CARTAO_CNPJ,
                FormatoEnvio.pdf, 1, linkCase.workspaceId() + "/" + linkCase.id() + "/" + id, "application/pdf", 1, 1,
                id.toString().replace("-", "") + "0".repeat(32), "c".repeat(64), at));
    }

    private static Instant months(int months) {
        return plusMonths(NOW, months);
    }

    private static Instant plusMonths(Instant instant, int months) {
        return instant.atOffset(ZoneOffset.UTC).plusMonths(months).toInstant();
    }
}
