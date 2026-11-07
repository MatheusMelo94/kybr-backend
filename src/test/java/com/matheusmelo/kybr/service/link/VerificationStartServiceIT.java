package com.matheusmelo.kybr.service.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.model.ClientIp;
import com.mongodb.MongoException;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.EstadoProcessamento;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OrigemSeller;
import com.matheusmelo.kybr.model.link.Seller;
import com.matheusmelo.kybr.model.link.StatusSeller;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.SellerRepository;
import com.matheusmelo.kybr.repository.SellerUpserts;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.idempotency.IdempotencyAttempt;
import com.matheusmelo.kybr.service.idempotency.IdempotencyProfile;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService;
import com.matheusmelo.kybr.support.link.RecordingDispatcher;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Starting a verification against MongoDB: one transaction, then dispatch after commit. */
class VerificationStartServiceIT extends WebIntegrationTest {

    private static final String CNPJ = "11222333000181";
    private static final ClientIp FORWARDED = new ClientIp("198.51.100.4", ClientIp.Source.FORWARDED);

    @Autowired
    private LinkRateLimits limits;
    @Autowired
    private IdempotencyService idempotency;
    @Autowired
    private LinkCaseRepository cases;
    @Autowired
    private SellerUpserts sellerUpserts;
    @Autowired
    private SellerRepository sellers;
    @Autowired
    private AuditEventRepository audits;
    @Autowired
    private WorkspaceRepository workspaces;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private Clock clock;

    private final RecordingDispatcher dispatcher = new RecordingDispatcher();
    private VerificationStartService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new VerificationStartService(limits, idempotency, cases, sellerUpserts, audits, new CaseTokens(),
                dispatcher, clock);
        workspace = workspaces.save(TestWorkspaces.builder().build());
    }

    @Test
    void createsTheCaseUpsertsTheSellerAuditsAndDispatchesAfterCommit() {
        String key = UUID.randomUUID().toString().toUpperCase();
        String ref = "~`\"\\ ref-" + "x".repeat(240);

        String token = service.start(workspace, FORWARDED,
                new StartCommand(CNPJ, "  José Exemplo ", ref, "ext-1", key), attempt(), t -> t);

        assertThat(token).matches("^[A-Za-z0-9_-]{43}$");
        LinkCase created = cases.findByTokenHashAndWorkspaceId(TestCases.hash(token), workspace.id()).orElseThrow();
        assertThat(created.estado()).isEqualTo(EstadoCaso.processing);
        assertThat(created.processamento().estado()).isEqualTo(EstadoProcessamento.PENDENTE);
        assertThat(created.responsavel()).isEqualTo("José Exemplo");
        assertThat(created.ref()).isEqualTo(ref);
        assertThat(created.extId()).isEqualTo("ext-1");
        assertThat(created.cnpj()).isEqualTo(CNPJ);
        assertThat(created.politica()).isEqualTo(workspace.politica());
        assertThat(created.idempotencyKey()).isEqualTo(key.toLowerCase());
        assertThat(mongo.getCollection(LinkCase.COLLECTION).find(new org.bson.Document("_id", created.id()))
                .first().toJson()).doesNotContain(token);

        Seller seller = sellers.findByWorkspaceIdAndCnpj(workspace.id(), CNPJ).orElseThrow();
        assertThat(seller.origem()).isEqualTo(OrigemSeller.link);
        assertThat(seller.status()).isEqualTo(StatusSeller.em_verificacao);

        List<AuditEvent> events = auditsOf(workspace);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.tipo()).isEqualTo(TipoAuditoria.VERIFICACAO_INICIADA);
            assertThat(event.ator()).isEqualTo(Ator.vendedor);
            assertThat(event.caseId()).isEqualTo(created.id());
            assertThat(event.clientIp()).isEqualTo("198.51.100.4");
            assertThat(event.ipDesconhecido()).isFalse();
            assertThat(event.sessionHash()).isNull();
            assertThat(event.resumo().cnpjMascarado()).isNotNull().doesNotContain(CNPJ).contains("*");
        });

        assertThat(dispatcher.submissions()).singleElement().satisfies(submission -> {
            assertThat(submission.caseId()).isEqualTo(created.id());
            assertThat(submission.inTransaction()).isFalse();
        });
    }

    @Test
    void aSharedIpIsAuditedAsUnknown() {
        service.start(workspace, new ClientIp("203.0.113.10", ClientIp.Source.FORWARDED_ABSENT),
                new StartCommand(CNPJ, "Maria Exemplo", null, null, UUID.randomUUID().toString()), attempt(),
                t -> t);

        assertThat(auditsOf(workspace)).singleElement().satisfies(event -> {
            assertThat(event.ipDesconhecido()).isTrue();
            assertThat(event.clientIp()).isNull();
        });
    }

    @Test
    void aSecondCaseForTheSameCnpjReusesTheSeller() {
        service.start(workspace, FORWARDED, new StartCommand(CNPJ, "Maria Exemplo", null, null,
                UUID.randomUUID().toString()), attempt(), t -> t);
        service.start(workspace, FORWARDED, new StartCommand(CNPJ, "Maria Exemplo", null, null,
                UUID.randomUUID().toString()), attempt(), t -> t);

        long sellerCount = mongo.count(Query.query(Criteria.where(Seller.WORKSPACE_ID).is(workspace.id())),
                Seller.COLLECTION);
        assertThat(sellerCount).isEqualTo(1);
        assertThat(dispatcher.submitted()).hasSize(2);
    }

    @Test
    void aTransientTransactionErrorIsRetriedAndCommitsOnce() {
        AtomicInteger upserts = new AtomicInteger();
        SellerUpserts conflicting = new SellerUpserts(mongo) {
            @Override
            public void markLinkVerificationStarted(UUID workspaceId, String cnpj) {
                super.markLinkVerificationStarted(workspaceId, cnpj);
                if (upserts.incrementAndGet() == 1) {
                    // What the driver raises when a concurrent transaction wrote the same seller.
                    MongoException conflict = new MongoException(112, "WriteConflict");
                    conflict.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                    throw new UncategorizedMongoDbException("write conflict", conflict);
                }
            }
        };
        VerificationStartService withConflict = new VerificationStartService(limits, idempotency, cases, conflicting,
                audits, new CaseTokens(), dispatcher, clock);

        String token = withConflict.start(workspace, FORWARDED,
                new StartCommand(CNPJ, "Maria Exemplo", null, null, UUID.randomUUID().toString()), attempt(),
                t -> t);

        assertThat(upserts).hasValue(2);
        Query byWorkspace = Query.query(Criteria.where("workspace_id").is(workspace.id()));
        assertThat(mongo.count(byWorkspace, LinkCase.COLLECTION)).isEqualTo(1);
        assertThat(mongo.count(byWorkspace, AuditEvent.COLLECTION)).isEqualTo(1);
        assertThat(cases.findByTokenHashAndWorkspaceId(TestCases.hash(token), workspace.id())).isPresent();
        assertThat(dispatcher.submissions()).hasSize(1);
    }

    @Test
    void aRollbackPersistsNothingAndDispatchesNothing() {
        assertThatThrownBy(() -> service.start(workspace, FORWARDED,
                new StartCommand(CNPJ, "Maria Exemplo", null, null, UUID.randomUUID().toString()), attempt(),
                t -> {
                    throw new IllegalStateException("synthetic failure after the writes");
                })).isInstanceOf(IllegalStateException.class);

        Query byWorkspace = Query.query(Criteria.where("workspace_id").is(workspace.id()));
        assertThat(mongo.count(byWorkspace, LinkCase.COLLECTION)).isZero();
        assertThat(mongo.count(byWorkspace, Seller.COLLECTION)).isZero();
        assertThat(mongo.count(byWorkspace, AuditEvent.COLLECTION)).isZero();
        assertThat(dispatcher.submissions()).isEmpty();
    }

    private IdempotencyAttempt attempt() {
        return ((IdempotencyService.Execute) idempotency.begin(IdempotencyProfile.PUBLIC_VERIFY, workspace.slug(),
                UUID.randomUUID().toString(), "synthetic-request-hash")).attempt();
    }

    private List<AuditEvent> auditsOf(Workspace owner) {
        return mongo.find(Query.query(Criteria.where(AuditEvent.WORKSPACE_ID).is(owner.id())), AuditEvent.class);
    }
}
