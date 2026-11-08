package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.CompanyCacheEntry;
import com.matheusmelo.kybr.model.link.Contato;
import com.matheusmelo.kybr.model.link.CreditLedger;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.EstadoProcessamento;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.TipoOutbox;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Plano;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.CreditLedgerOps;
import com.matheusmelo.kybr.repository.DocumentRepository;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.OutboxClaims;
import com.matheusmelo.kybr.repository.OutboxEventRepository;
import com.matheusmelo.kybr.repository.RetentionClaims;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.MetricTags;
import com.matheusmelo.kybr.service.company.ProviderBudgets;
import com.matheusmelo.kybr.service.company.RuntimeFlagsService;
import com.matheusmelo.kybr.service.crypto.FieldCipher;
import com.matheusmelo.kybr.service.email.EmailService;
import com.matheusmelo.kybr.service.email.NotificationDispatcher;
import com.matheusmelo.kybr.service.email.TemplateRenderer;
import com.matheusmelo.kybr.service.email.TestDispatchers;
import com.matheusmelo.kybr.service.ratelimit.RateLimiter;
import com.matheusmelo.kybr.service.retention.OrphanReconciliationJob;
import com.matheusmelo.kybr.service.retention.RetentionJob;
import com.matheusmelo.kybr.service.scan.ClamdScanner;
import com.matheusmelo.kybr.service.scan.ScanLinkTls;
import com.matheusmelo.kybr.service.scan.ScannerWake;
import com.matheusmelo.kybr.service.scan.SignatureMonitor;
import com.matheusmelo.kybr.service.storage.DocumentStorage;
import com.matheusmelo.kybr.service.storage.S3DocumentStorage;
import com.matheusmelo.kybr.service.upload.UploadService;
import com.matheusmelo.kybr.service.upload.scan.ScanDispatcher;
import com.matheusmelo.kybr.service.upload.scan.ScanWorker;
import com.matheusmelo.kybr.support.MovableClock;
import com.matheusmelo.kybr.support.email.ScriptedEmailSender;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.tls.FakeClamd;
import com.matheusmelo.kybr.support.tls.TestPki;
import com.matheusmelo.kybr.support.upload.ScriptedStorage;
import com.matheusmelo.kybr.support.upload.StubS3Server;
import com.matheusmelo.kybr.support.upload.TestFiles;
import com.matheusmelo.kybr.support.upload.TestImages;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import com.matheusmelo.kybr.support.upload.UploadWebIntegrationTest;
import com.matheusmelo.kybr.support.web.LimiterClock;
import com.matheusmelo.kybr.util.EmailMasker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The metrics the alert rules fire on: each one is registered and moves under its trigger, no
 * metric name or tag value carries a CNPJ, an e-mail, an IP, a case token or a filename, and the
 * document, e-mail and retention metrics tag only with fixed enumerations. The registry checks drive
 * every scenario themselves, so each test passes alone and in any order.
 */
class MetricsCatalogueIT extends UploadWebIntegrationTest {

    /** Every application metric an alert rule or dashboard reads. */
    static final List<String> CATALOGUE = List.of(
            "kybr.link.lookup",
            "kybr.link.verify",
            "kybr.ratelimit.exceeded",
            "kybr.client_ip.absent",
            "kybr.verification.no_data",
            "kybr.verification.no_data_cap",
            "kybr.provider.calls",
            "kybr.provider.credits.used_ratio",
            "kybr.provider.budget_exhausted",
            "kybr.provider.budget_warning",
            "kybr.provider.spend_off",
            "kybr.link.autofill_off",
            "kybr.link.lookup.cache_reads",
            "kybr.link.contact",
            "kybr.verification.duration",
            "kybr.verification.deferred",
            "kybr.verification.failed",
            "kybr.idempotency.completion_missing",
            "kybr.admin.auth_failed",
            "kybr.upload",
            "kybr.upload.malware",
            "kybr.image.reencode",
            "kybr.scanner.calls",
            "kybr.scanner.signature_age_hours",
            "kybr.storage.calls",
            "kybr.email.sent",
            "kybr.email.capped",
            "kybr.email.failed",
            "kybr.retention.processed",
            "kybr.retention.failed",
            "kybr.retention.orphans_deleted",
            "kybr.scan.result",
            "kybr.scan.wake_seconds",
            "kybr.scan.backlog",
            "kybr.scan.backlog_oldest_age_seconds",
            "kybr.scan.stuck");

    private static final String FOUND = "12345678000195";
    private static final String OTHER_FOUND = "25873916000187";
    private static final String NOT_FOUND = "71438562000160";
    private static final String UNAVAILABLE = "52000015000153";
    private static final String UNSUPPORTED = "5200A000000100";
    private static final String CLIENT_IP = "X-Kybr-Client-IP";
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final Duration WAIT = Duration.ofSeconds(15);

    /** The multipart filename every upload here sends; no metric may carry it. */
    private static final String FILENAME = "documento-metricas.bin";
    /** The tag keys the document, scanner, storage, e-mail and retention metrics may use. */
    private static final Set<String> BOUNDED_TAG_KEYS = Set.of("resultado", "operacao", "tipo", "motivo", "estado");
    private static final Pattern BOUNDED_TAG_VALUE = Pattern.compile("[a-z][a-z_-]*");
    /**
     * The live signature monitor, built once against the context's registry (the test profile runs
     * the stub scanner, which has no signature database). Held here so the gauge's weak reference
     * stays live across tests.
     */
    private static SignatureMonitor signatures;

    private static final Pattern CNPJ = Pattern.compile(
            "\\d{14}|\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}|[0-9A-Z]{12}\\d{2}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+");
    private static final Pattern IP = Pattern.compile(
            "(^|[^0-9.])\\d{1,3}(\\.\\d{1,3}){3}([^0-9.]|$)|[0-9a-fA-F]{0,4}(:[0-9a-fA-F]{0,4}){2,}");

    @Autowired
    private MeterRegistry meters;
    @Autowired
    private ScanDispatcher scans;
    @Autowired
    private WorkspaceRepository workspaces;
    @Autowired
    private LinkCaseRepository cases;
    @Autowired
    private ProviderBudgets budgets;
    @Autowired
    private CreditLedgerOps ledger;
    @Autowired
    private RuntimeFlagsService flags;

    @Autowired
    private RetentionClaims retentionClaims;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private AuditEventRepository audits;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OutboxClaims outboxClaims;
    @Autowired
    private FieldCipher cipher;
    @Autowired
    private RateLimiter limiter;

    @LocalManagementPort
    private int managementPort;

    @BeforeEach
    void freshBudgetsAndSwitches() {
        resetBudgets();
        flags.setAutofill(true);
        flags.setProviderSpend(true);
    }

    @AfterEach
    void leaveNoSpentBudgetBehind() {
        resetBudgets();
        flags.setAutofill(true);
        flags.setProviderSpend(true);
    }

    @Test
    void lookupOutcomesProviderCallsAndCacheReadsAreCounted() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());
        Workspace disabled = workspaces.save(TestWorkspaces.builder().slug(newSlug()).autofillAtivo(false).build());
        Workspace trial = workspaces.save(TestWorkspaces.builder().slug(newSlug()).plano(Plano.Tipo.teste).build());
        mongo.remove(new Query(), CompanyCacheEntry.class); // so the first lookups reach the provider

        assertMoves(() -> lookups(workspace, "fresh"), () -> lookup(workspace, FOUND));
        assertMoves(() -> providerCalls("basic", "found"), () -> lookup(workspace, OTHER_FOUND));
        assertMoves(() -> lookups(workspace, "hit"), () -> lookup(workspace, FOUND));
        assertMoves(() -> count("kybr.link.lookup.cache_reads", "slug", workspace.slug()),
                () -> lookup(workspace, FOUND));
        assertMoves(() -> lookups(workspace, "not_found"), () -> lookup(workspace, NOT_FOUND));
        assertMoves(() -> lookups(workspace, "invalid"), () -> lookup(workspace, "not-a-cnpj"));
        assertMoves(() -> lookups(disabled, "disabled"), () -> lookup(disabled, FOUND));
        assertMoves(() -> lookups(trial, "cache_only_miss"), () -> lookup(trial, "98765432000198"));
        assertMoves(() -> providerCalls("basic", "error"), () -> lookup(workspace, UNAVAILABLE));
        assertMoves(() -> lookups(workspace, "error"), () -> lookup(workspace, UNAVAILABLE));
    }

    @Test
    void aVerificationCountsItsStartItsProviderCallAndItsDuration() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());

        assertMoves(() -> count("kybr.link.verify", "slug", workspace.slug(), "resultado", "accepted"),
                () -> verifyAndWait(workspace, FOUND));
        assertMoves(() -> providerCalls("full_check", "found"), () -> verifyAndWait(workspace, "11222333000181"));
        assertMoves(this::durations, () -> verifyAndWait(workspace, FOUND));

        String key = UUID.randomUUID().toString();
        String body = "{\"cnpj\":\"" + FOUND + "\",\"responsavel\":\"Maria Exemplo\"}";
        String ip = randomIp();
        assertThat(verify(workspace, key, body, ip).statusCode()).isEqualTo(201);
        // A replay answers before the slug is resolved, so it is never attributed to a link.
        assertMoves(() -> count("kybr.link.verify", "slug", MetricTags.UNKNOWN, "resultado", "replayed"),
                () -> assertThat(verify(workspace, key, body, ip).statusCode()).isEqualTo(201));
        assertMoves(() -> count("kybr.link.verify", "slug", workspace.slug(), "resultado", "rejected"),
                () -> assertThat(verify(workspace, UUID.randomUUID().toString(),
                        "{\"cnpj\":\"11111111111111\",\"responsavel\":\"Maria Exemplo\"}", ip).statusCode())
                        .isEqualTo(422));
        assertMoves(() -> count("kybr.link.verify", "slug", MetricTags.UNKNOWN, "resultado", "rejected"),
                () -> assertThat(verify(workspace, key, body.replace("Maria", "Ana"), ip).statusCode())
                        .isEqualTo(422));
        assertThat(meters.find("kybr.verification.duration").timers()).allSatisfy(timer -> assertThat(
                timer.takeSnapshot().histogramCounts()).extracting(bucket -> bucket.bucket(TimeUnit.SECONDS))
                .as("a histogram with a 5 s bucket, for a real p90 and the 5 s target").contains(5.0));
    }

    @Test
    void noDataTheNoDataCapDeferralsAndFailuresAreCounted() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());
        Workspace capped = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());

        assertMoves(() -> count("kybr.verification.no_data", "slug", workspace.slug()),
                () -> verifyAndWait(workspace, NOT_FOUND));
        assertMoves(() -> count("kybr.verification.failed", "motivo", "PROVEDOR_NAO_SUPORTA"),
                () -> verifyAndWait(workspace, UNSUPPORTED));

        exhaust("no_data", () -> budgets.recordNoData(capped.slug()));
        assertMoves(() -> count("kybr.verification.no_data_cap", "slug", capped.slug()),
                () -> verifyAndWait(capped, FOUND));

        flags.setProviderSpend(false);
        assertThat(gauge("kybr.provider.spend_off")).isEqualTo(1.0);
        assertMoves(() -> count("kybr.verification.deferred", "motivo", "GASTO_DESLIGADO"),
                () -> verifyAndWait(workspace, FOUND));
        flags.setProviderSpend(true);
        assertThat(gauge("kybr.provider.spend_off")).isEqualTo(0.0);

        flags.setAutofill(false);
        assertThat(gauge("kybr.link.autofill_off")).isEqualTo(1.0);
        flags.setAutofill(true);
        assertThat(gauge("kybr.link.autofill_off")).isEqualTo(0.0);
    }

    @Test
    void everyBudgetWarnsAndThenReportsExhaustion() {
        String slug = newSlug();

        exhaust("d2_slug", () -> budgets.bookD2(slug));
        exhaust("d2_daily", () -> budgets.bookD2(newSlug()));
        exhaust("d3_slug", () -> budgets.bookD3(slug));
        Workspace spent = workspaces.save(TestWorkspaces.builder().slug(slug).build());
        mongo.remove(Query.query(Criteria.where("_id").is("98765432000198")), CompanyCacheEntry.class);
        assertMoves(() -> providerCalls("basic", "budget_denied"), () -> lookup(spent, "98765432000198"));
        assertMoves(() -> providerCalls("full_check", "budget_denied"), () -> verifyAndWait(spent, FOUND));
        exhaust("d3_daily", () -> budgets.bookD3(newSlug()));
        exhaust("no_data", () -> budgets.recordNoData(slug));
        // The month's budgets are large: book up to just below each threshold in one go, then let a
        // real booking cross it.
        long monthly = properties.cnpja().monthlyCredits();
        long d2Share = Math.round(monthly * properties.link().budgets().d2Share());
        topUp(CreditLedgerOps.Use.D2, d2Share * 8 / 10 - 1, CreditLedger::d2);
        bookUntilMoved("kybr.provider.budget_warning", "d2_share", () -> budgets.bookD2(newSlug()));
        topUp(CreditLedgerOps.Use.D2, d2Share - 1, CreditLedger::d2);
        bookUntilMoved("kybr.provider.budget_exhausted", "d2_share", () -> budgets.bookD2(newSlug()));
        topUp(CreditLedgerOps.Use.D3, monthly * 8 / 10 - 1, CreditLedger::usados);
        bookUntilMoved("kybr.provider.budget_warning", "monthly", () -> budgets.bookD3(newSlug()));
        topUp(CreditLedgerOps.Use.D3, monthly - 1, CreditLedger::usados);
        bookUntilMoved("kybr.provider.budget_exhausted", "monthly", () -> budgets.bookD3(newSlug()));

        assertThat(gauge("kybr.provider.credits.used_ratio")).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void rateLimitsContactChurnTheIdempotencyGuardAndAdminAuthFailuresAreCounted() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());
        String ip = randomIp();

        assertMoves(() -> sum("kybr.ratelimit.exceeded"), () -> {
            for (int i = 0; i < properties.link().limits().lookupIp().limit() + 1; i++) {
                http().header(CLIENT_IP, ip).queryParam("cnpj", FOUND).get("/public/v/{slug}/lookup", workspace.slug());
            }
        });

        TestCases.Built built = TestCases.builder(workspace).build();
        cases.save(built.linkCase());
        assertMoves(() -> count("kybr.link.contact", "resultado", "changed"), () -> http().header(CLIENT_IP, randomIp())
                .contentType("application/json").body("{\"email\":\"ana@empresa-exemplo.test\"}")
                .put("/public/v/{slug}/cases/{token}/contact", workspace.slug(), built.token()));

        assertMoves(() -> count("kybr.idempotency.completion_missing"), () -> http()
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .body("{\"name\":\"Loja Exemplo\"}").post("/public/v/{slug}/forgetful-test", newSlug()));

        assertMoves(() -> count("kybr.admin.auth_failed"),
                () -> RestAssured.given().port(managementPort).get("/actuator/admin/x"));
    }

    @Test
    void aMissingClientIpCountsAsExpectedOnlyWhereTheRouteExpectsOne() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());
        double expected = count("kybr.client_ip.absent", "esperado", "true");
        double notExpected = count("kybr.client_ip.absent", "esperado", "false");

        http().get("/public/v/{slug}", workspace.slug());

        assertThat(count("kybr.client_ip.absent", "esperado", "false")).isEqualTo(notExpected + 1);
        assertThat(count("kybr.client_ip.absent", "esperado", "true")).isEqualTo(expected);

        http().queryParam("cnpj", FOUND).get("/public/v/{slug}/lookup", workspace.slug());

        assertThat(count("kybr.client_ip.absent", "esperado", "true")).isEqualTo(expected + 1);
        assertThat(count("kybr.client_ip.absent", "esperado", "false")).isEqualTo(notExpected + 1);
    }

    @Test
    void uploadsMalwareAndPhotoReencodesAreCounted() {
        // its own rate-limit window, so the shared loopback address never runs into the per-IP limit
        LimiterClock.INSTANCE.freeze(Instant.now().plus(Duration.ofDays(ThreadLocalRandom.current().nextInt(1, 3_650))));
        try {
            assertMoves(() -> count(UploadService.METRIC, "resultado", "aceito"),
                    () -> upload(TIPO, TestImages.jpegWithMetadata(32, 24)));
            assertMoves(() -> count(ScanWorker.REENCODE_METRIC, "resultado", "ok"), () -> {
                upload(TIPO, TestImages.png(20, 20));
                scans.runOnce();
            });
            assertMoves(() -> count(UploadService.METRIC, "resultado", "formato"),
                    () -> upload(TIPO, TestFiles.eicar()));
            scans.runOnce();
            scanner.infectedOnCall(scanner.calls() + 1, "Kybr.Test.Signature");
            assertMoves(() -> count(ScanWorker.MALWARE_METRIC), () -> {
                upload(OTHER_TIPO, TestPdfs.plain());
                scans.runOnce();
            });
        } finally {
            LimiterClock.INSTANCE.release();
        }
    }

    /**
     * The live scanner and storage count their own calls; the test profile runs their stand-ins, so
     * they are built here against the test servers and the context's registry.
     */
    @Test
    void scanSessionsCountTheirWakeUpsAndOutcomesAndTheBacklogIsAGauge() {
        // its own rate-limit window, so the shared loopback address never runs into the per-IP limit
        LimiterClock.INSTANCE.freeze(Instant.now().plus(Duration.ofDays(ThreadLocalRandom.current().nextInt(1, 3_650))));
        TestCases.Built fresh = seedCase(EstadoCaso.pending);
        try {
            assertMoves(() -> count(ScanWorker.RESULT_METRIC, "resultado", "aceito"), () -> {
                upload(fresh, TIPO, TestPdfs.plain());
                scans.runOnce();
            });
            assertThat(meters.get(ScannerWake.METRIC).tag("resultado", "ok").timer().count()).isPositive();
            assertMoves(() -> count(ScanWorker.RESULT_METRIC, "resultado", "recusado_conteudo"), () -> {
                upload(fresh, OTHER_TIPO, TestPdfs.withScannerMarker());
                scans.runOnce();
            });
            scanner.unavailable();
            assertMoves(() -> count(ScanWorker.RESULT_METRIC, "resultado", "reagendado"), () -> {
                upload(fresh, OTHER_TIPO, TestImages.jpeg(9, 9));
                scans.runOnce();
            });
            mongo.updateMulti(query(where(Document.CASE_ID).is(fresh.linkCase().id())
                            .and(Document.ESTADO).is(EstadoDocumento.RECEBIDO)),
                    new Update().set(Document.TENTATIVAS, 5).set(Document.PROXIMA_TENTATIVA, Instant.now()),
                    Document.class);
            assertMoves(() -> count(ScanWorker.RESULT_METRIC, "resultado", "recusado_falhou"), () -> scans.runOnce());
        } finally {
            scanner.reset();
            LimiterClock.INSTANCE.release();
        }
        assertThat(meters.get(ScanDispatcher.BACKLOG_GAUGE).tag("estado", "recebido").gauge()).isNotNull();
        assertThat(meters.get(ScanDispatcher.OLDEST_GAUGE).gauge()).isNotNull();
        assertThat(meters.get(ScanDispatcher.STUCK_GAUGE).gauge()).isNotNull();
    }

    @Test
    void theLiveScannerAndStorageCountTheirCallsAndTheSignatureAgeIsAGauge() throws IOException {
        TestPki pki = TestPki.authority("Metrics Test CA");
        TestPki.Identity server = pki.issue("scanner", "dns:localhost,ip:127.0.0.1");
        TestPki.Identity client = pki.issue("api-client", null);
        byte[] pdf = TestPdfs.plain();
        try (FakeClamd clamd = new FakeClamd(pki.serverContext(server)).replying("stream: OK\0")) {
            ClamdScanner live = new ClamdScanner(
                    ScanLinkTls.fromPem(client.certificatePem(), client.keyPem(), pki.caPem(), server.spkiSha256()),
                    "localhost", clamd.port(), Duration.ofSeconds(3), Duration.ofSeconds(5), meters);
            assertMoves(() -> count(ClamdScanner.METRIC, "resultado", "clean"),
                    () -> live.scan(new ByteArrayInputStream(pdf), pdf.length));
        }

        try (StubS3Server s3 = new StubS3Server()) {
            KybrProperties.Storage settings = new KybrProperties.Storage(KybrProperties.Storage.Mode.S3, "kybr-docs-test",
                    "sa-east-1", "arn:aws:kms:sa-east-1:000000000000:key/00000000-0000-4000-8000-000000000000",
                    "kybr-quarantine-test", "arn:aws:kms:sa-east-1:000000000000:key/00000000-0000-4000-8000-000000000001",
                    null, "sts.amazonaws.com", "test-only-access-key-id", "test-only-secret-access-key-0000000000000",
                    s3.endpoint(), Duration.ofSeconds(2), Duration.ofSeconds(5));
            S3DocumentStorage live = new S3DocumentStorage(S3Config.s3Client(settings),
                    new S3DocumentStorage.Bucket(settings.bucket(), settings.kmsKeyId()),
                    new S3DocumentStorage.Bucket(settings.quarantineBucket(), settings.quarantineKmsKeyId()), meters);
            String key = DocumentStorage.key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            assertMoves(() -> count(S3DocumentStorage.METRIC, "operacao", "put", "resultado", "ok"),
                    () -> live.put(DocumentStorage.Area.FINAL, key, new ByteArrayInputStream(pdf), pdf.length, "application/pdf", Map.of()));
        }

        signatureMonitor().check();
        assertThat(gauge(SignatureMonitor.GAUGE)).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void sentCappedAndFailedEmailsAreCountedByType() {
        mongo.remove(new Query(), OutboxEvent.class); // the dispatcher claims every pending event
        ScriptedEmailSender sender = new ScriptedEmailSender();
        EmailService emails = new EmailService(new TemplateRenderer(), limiter, sender,
                new KybrProperties.Email.Caps(1, 10), meters);
        NotificationDispatcher dispatcher = TestDispatchers.withNoticeOn(outboxClaims, cases, cipher, emails,
                Clock.systemUTC(), meters);
        String once = newAddress();

        assertMoves(() -> count("kybr.email.sent", "tipo", "contato-alterado"), () -> {
            queueAddressChange(once);
            dispatcher.runOnce();
        });
        assertMoves(() -> count("kybr.email.capped", "tipo", "contato-alterado"), () -> {
            queueAddressChange(once);
            dispatcher.runOnce();
        });
        sender.permanentOnCall(sender.calls().size() + 1);
        assertMoves(() -> count("kybr.email.failed", "tipo", "contato-alterado", "motivo", "permanente"), () -> {
            queueAddressChange(newAddress());
            dispatcher.runOnce();
        });
    }

    /** The jobs run here on a scripted store, so a deletion can be made to fail. */
    @Test
    void retentionRunsFailuresAndOrphanDeletionsAreCounted() {
        MovableClock clock = new MovableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        ScriptedStorage store = new ScriptedStorage(clock);
        RetentionJob retention = new RetentionJob(retentionClaims, documents, outbox, audits, store,
                transactionManager, properties, clock, meters);
        OrphanReconciliationJob orphans = new OrphanReconciliationJob(workspaces, documents, audits, store, properties,
                clock, meters);

        store.failDeleteOnCall(1);
        assertMoves(() -> count("kybr.retention.failed"), () -> {
            dueCase(store, clock);
            retention.runOnce();
        });
        assertMoves(() -> count("kybr.retention.processed"), () -> {
            dueCase(store, clock);
            retention.runOnce();
        });

        Workspace owner = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());
        store.put(DocumentStorage.Area.FINAL, DocumentStorage.key(owner.id(), UUID.randomUUID(), UUID.randomUUID()),
                new ByteArrayInputStream(new byte[] {1}), 1, "application/pdf", Map.of());
        clock.advance(properties.retention().orphanMinAge().plusSeconds(1));
        assertMoves(() -> count("kybr.retention.orphans_deleted"), orphans::runOnce);
    }

    @Test
    void randomSlugsAddNoApplicationTimeSeries() {
        lookupUnknownSlug();
        Set<Meter.Id> before = meterIds();
        Set<String> slugs = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            String slug = newSlug();
            slugs.add(slug);
            http().header(CLIENT_IP, randomIp()).queryParam("cnpj", FOUND).get("/public/v/{slug}/lookup", slug);
        }

        Set<Meter.Id> added = new HashSet<>(meterIds());
        added.removeAll(before);
        assertThat(added).filteredOn(id -> id.getName().startsWith("kybr.") || id.getTag(MetricTags.SLUG) != null)
                .isEmpty();
        assertThat(meters.getMeters()).flatExtracting(meter -> meter.getId().getTags()).extracting(Tag::getValue)
                .doesNotContainAnyElementsOf(slugs);
    }

    @Test
    void everyCatalogueMetricIsRegistered() {
        driveEveryScenario();
        Set<String> registered = meters.getMeters().stream().map(meter -> meter.getId().getName())
                .collect(Collectors.toSet());

        assertThat(registered).containsAll(CATALOGUE);
    }

    @Test
    void noMetricNameOrTagValueCarriesACnpjAnEmailAnIpATokenOrAFilename() {
        driveEveryScenario();
        for (Meter meter : meters.getMeters()) {
            Meter.Id id = meter.getId();
            assertThat(id.getName()).doesNotContainPattern(CNPJ).doesNotContainPattern(EMAIL).doesNotContainPattern(IP);
            for (Tag tag : id.getTags()) {
                assertThat(tag.getValue()).as(id.getName() + "{" + tag.getKey() + "}")
                        .doesNotContainPattern(CNPJ).doesNotContainPattern(EMAIL)
                        .doesNotContain(pending.token()).doesNotContain(FILENAME);
                if (!isDatabaseServerAddress(id, tag)) {
                    assertThat(tag.getValue()).as(id.getName() + "{" + tag.getKey() + "}").doesNotContainPattern(IP);
                }
            }
        }
    }

    /** The document, scanner, storage, e-mail and retention metrics tag only with fixed enumerations. */
    @Test
    void documentAndEmailMetricsUseOnlyBoundedTags() {
        driveEveryScenario();
        List<Meter.Id> ids = meters.getMeters().stream().map(Meter::getId)
                .filter(id -> id.getName().matches("kybr\\.(upload|image|scanner|scan|storage|email|retention)(\\..*)?"))
                .toList();

        assertThat(ids).isNotEmpty();
        for (Meter.Id id : ids) {
            for (Tag tag : id.getTags()) {
                assertThat(tag.getKey()).as(id.getName()).isIn(BOUNDED_TAG_KEYS);
                assertThat(tag.getValue()).as(id.getName() + "{" + tag.getKey() + "}").matches(BOUNDED_TAG_VALUE);
            }
        }
    }

    /** Runs every scenario that registers a catalogue metric, each from fresh budgets and switches. */
    private void driveEveryScenario() {
        List<Runnable> scenarios = List.of(this::lookupOutcomesProviderCallsAndCacheReadsAreCounted,
                this::aVerificationCountsItsStartItsProviderCallAndItsDuration,
                this::noDataTheNoDataCapDeferralsAndFailuresAreCounted,
                this::everyBudgetWarnsAndThenReportsExhaustion,
                this::rateLimitsContactChurnTheIdempotencyGuardAndAdminAuthFailuresAreCounted,
                this::aMissingClientIpCountsAsExpectedOnlyWhereTheRouteExpectsOne,
                this::uploadsMalwareAndPhotoReencodesAreCounted,
                this::scanSessionsCountTheirWakeUpsAndOutcomesAndTheBacklogIsAGauge,
                this::liveAdapters,
                this::sentCappedAndFailedEmailsAreCountedByType,
                this::retentionRunsFailuresAndOrphanDeletionsAreCounted);
        for (Runnable scenario : scenarios) {
            freshBudgetsAndSwitches();
            scenario.run();
        }
        leaveNoSpentBudgetBehind();
    }

    /** The driver's own pool and command meters name the database server; that is no client's IP. */
    private static boolean isDatabaseServerAddress(Meter.Id id, Tag tag) {
        return id.getName().startsWith("mongodb.driver.") && tag.getKey().equals("server.address");
    }

    private void liveAdapters() {
        try {
            theLiveScannerAndStorageCountTheirCallsAndTheSignatureAgeIsAGauge();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SignatureMonitor signatureMonitor() {
        if (signatures == null) {
            signatures = new SignatureMonitor(() -> Optional.of(Instant.now().minus(Duration.ofHours(3))),
                    Duration.ofHours(48), Clock.systemUTC(), meters);
        }
        return signatures;
    }

    private void upload(DocumentoTipo tipo, byte[] file) {
        upload(pending, tipo, file);
    }

    private void upload(TestCases.Built linkCase, DocumentoTipo tipo, byte[] file) {
        httpWithoutKey().multiPart("tipo", tipo.name())
                .multiPart("arquivos", FILENAME, file, "application/octet-stream")
                .post("/public/v/{slug}/cases/{token}/documents", workspace.slug(), linkCase.token());
    }

    private static String newAddress() {
        return "antigo." + UUID.randomUUID() + "@example.test";
    }

    /** A case whose contact changed away from {@code oldAddress}, with its pending notice in the outbox. */
    private void queueAddressChange(String oldAddress) {
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String current = "novo." + UUID.randomUUID() + "@example.test";
        cases.save(TestCases.builder(TestWorkspaces.builder().slug(newSlug()).build()).id(caseId)
                .contato(new Contato(cipher.encrypt(current, caseId), EmailMasker.mask(current), now))
                .build().linkCase());
        UUID eventId = UUID.randomUUID();
        mongo.insert(new OutboxEvent(eventId, TipoOutbox.CONTATO_ALTERADO, caseId, cipher.encrypt(oldAddress, eventId),
                now, now.plus(Duration.ofDays(30))));
    }

    /** A case past its retention date holding one stored document. */
    private void dueCase(ScriptedStorage store, Clock clock) {
        Workspace owner = workspaces.save(TestWorkspaces.builder().slug(newSlug()).build());
        UUID caseId = UUID.randomUUID();
        cases.save(TestCases.builder(owner).id(caseId).retencaoAte(clock.instant().minusSeconds(1))
                .criadoEm(clock.instant().minus(Duration.ofDays(400))).build().linkCase());
        UUID documentId = UUID.randomUUID();
        String key = DocumentStorage.key(owner.id(), caseId, documentId);
        store.put(DocumentStorage.Area.FINAL, key, new ByteArrayInputStream(new byte[] {1}), 1, "application/pdf", Map.of());
        documents.save(new Document(documentId, owner.id(), caseId, null, DocumentoTipo.CARTAO_CNPJ, FormatoEnvio.pdf,
                1, key, "application/pdf", 1, 1, documentId.toString().replace("-", "") + "0".repeat(32),
                "d".repeat(64), clock.instant()));
    }

    private Response lookup(Workspace workspace, String cnpj) {
        return http().header(CLIENT_IP, randomIp()).queryParam("cnpj", cnpj)
                .get("/public/v/{slug}/lookup", workspace.slug());
    }

    private void lookupUnknownSlug() {
        http().header(CLIENT_IP, randomIp()).queryParam("cnpj", FOUND).get("/public/v/{slug}/lookup", newSlug());
    }

    private Response verify(Workspace workspace, String key, String body, String ip) {
        return http().header("Idempotency-Key", key).header(CLIENT_IP, ip).contentType("application/json")
                .body(body).post("/public/v/{slug}/verify", workspace.slug());
    }

    /** Starts a verification and waits until the worker has written a verdict or sent it to review. */
    private void verifyAndWait(Workspace workspace, String cnpj) {
        Response response = verify(workspace, UUID.randomUUID().toString(),
                "{\"cnpj\":\"" + cnpj + "\",\"responsavel\":\"Maria Exemplo\"}", randomIp());
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(201);
        String hash = TestCases.hash(response.jsonPath().getString("token"));
        awaitUntil(() -> {
            LinkCase linkCase = mongo.findOne(Query.query(Criteria.where(LinkCase.TOKEN_HASH).is(hash)), LinkCase.class);
            return linkCase != null && (linkCase.processamento().estado() == EstadoProcessamento.CONCLUIDO
                    || linkCase.processamento().estado() == EstadoProcessamento.REVISAO_MANUAL);
        });
    }

    /** Runs {@code step} until the budget's warning, then its exhaustion, counter moves. */
    private void exhaust(String budget, Runnable step) {
        bookUntilMoved("kybr.provider.budget_warning", budget, step);
        bookUntilMoved("kybr.provider.budget_exhausted", budget, step);
    }

    private void bookUntilMoved(String metric, String budget, Runnable step) {
        double before = count(metric, "budget", budget);
        for (int i = 0; count(metric, "budget", budget) == before; i++) {
            assertThat(i).as("bookings until " + metric + "{" + budget + "}").isLessThan(1_000);
            step.run();
        }
    }

    /** Books this month's credits directly until {@code field} reads {@code target}. */
    private void topUp(CreditLedgerOps.Use use, long target, ToLongFunction<CreditLedger> field) {
        String month = CreditLedger.idFor(YearMonth.now(SAO_PAULO));
        long current = ledger.month(month).map(field::applyAsLong).orElse(0L);
        assertThat(current).as("already booked").isLessThanOrEqualTo(target);
        if (current < target) {
            ledger.book(month, use, target - current, 0, 0);
        }
    }

    private void resetBudgets() {
        mongo.dropCollection(CreditLedger.COLLECTION);
        mongo.remove(Query.query(Criteria.where("_id").regex("^budget\\.")), "rate_limit_counters");
    }

    /** The worker counts after it writes the case, so a metric may move just after the trigger returns. */
    private static void assertMoves(DoubleSupplier metric, Runnable trigger) {
        double before = metric.getAsDouble();
        trigger.run();
        awaitUntil(() -> metric.getAsDouble() > before);
    }

    private double lookups(Workspace workspace, String resultado) {
        return count("kybr.link.lookup", "slug", workspace.slug(), "resultado", resultado);
    }

    private double providerCalls(String tipo, String resultado) {
        return count("kybr.provider.calls", "tipo", tipo, "resultado", resultado);
    }

    private double count(String name, String... tags) {
        Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double sum(String name) {
        return meters.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private double durations() {
        return meters.find("kybr.verification.duration").timers().stream().mapToLong(Timer::count).sum();
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private Set<Meter.Id> meterIds() {
        return meters.getMeters().stream().map(Meter::getId).collect(Collectors.toSet());
    }

    private static String newSlug() {
        return "loja-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void awaitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + WAIT);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
