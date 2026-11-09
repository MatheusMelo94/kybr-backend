package com.matheusmelo.kybr.service.verification;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.ModoConsulta;
import com.matheusmelo.kybr.model.link.MotivoRevisao;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.link.Revisao;
import com.matheusmelo.kybr.model.link.Variante;
import com.matheusmelo.kybr.model.workspace.Chave;
import com.matheusmelo.kybr.model.workspace.PoliticaRegra;
import com.matheusmelo.kybr.repository.ProcessingClaims;
import com.matheusmelo.kybr.repository.SellerUpserts;
import com.matheusmelo.kybr.repository.VerificationRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.MetricTags;
import com.matheusmelo.kybr.service.TransactionRetry;
import com.matheusmelo.kybr.service.company.CompanyDataProvider;
import com.matheusmelo.kybr.service.company.CompanyDataResult;
import com.matheusmelo.kybr.service.company.CompanyRecord;
import com.matheusmelo.kybr.service.company.ProviderBudgets;
import com.matheusmelo.kybr.service.company.ProviderBudgets.Budget;
import com.matheusmelo.kybr.service.company.ProviderBudgets.Decision;
import com.matheusmelo.kybr.service.verification.VerdictEngine.Verdict;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies one claimed case: budgets, the provider's rate gate, the full check, the checks and the
 * verdict, written in one transaction fenced on the claim.
 *
 * <ol>
 *   <li>Past the maximum attempts (earlier attempts ended unexpectedly) → manual review
 *       ({@code FALHA_PROCESSAMENTO}), without a provider call.
 *   <li>A spent budget (monthly credits, the daily verification budgets, the link's "no data" cap)
 *       or provider spend off → manual review with its reason, without a provider call.
 *   <li>The provider's per-minute gate denied → back in the queue at its retry-after.
 *   <li>The full check: found and complete → verdict; found but missing the company name, state,
 *       status or opening date (a value of the wrong shape counts as missing), or opened after today (a data anomaly) → manual review
 *       ({@code DADOS_INCOMPLETOS}); not found → the "no data" variant and manual review;
 *       unsupported → manual review, no retry; rate limited or
 *       unavailable → retried with backoff (never sooner than the retry-after), then manual review
 *       ({@code PROVEDOR_INDISPONIVEL}).
 *   <li>The verdict: the verification, the case result and the seller status in one transaction;
 *       a lost claim aborts it, so nothing is written.
 * </ol>
 *
 * <p>Every attempt counts toward the maximum except one the provider gate turned away. An
 * unexpected failure is retried with backoff the same way, then goes to manual review
 * ({@code FALHA_PROCESSAMENTO}); a claim that expired mid-attempt is caught by the first rule, so no case
 * calls the provider more than the maximum attempts. Every non-final outcome leaves the seller's view at "processing". No provider body or CNPJ is
 * ever logged.
 */
@Service
public class VerificationWorker {

    private static final Logger log = LoggerFactory.getLogger(VerificationWorker.class);
    private static final int DEFAULT_RECENT_DAYS = 180;
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    /** The result-within-5-s target the duration alert watches. */
    static final Duration DURATION_TARGET = Duration.ofSeconds(5);

    private final ProcessingClaims claims;
    private final WorkspaceRepository workspaces;
    private final VerificationRepository verifications;
    private final SellerUpserts sellers;
    private final CompanyDataProvider provider;
    private final ProviderBudgets budgets;
    private final ChecksEvaluator evaluator;
    private final VerdictEngine engine;
    private final TransactionTemplate transactions;
    private final int maxAttempts;
    private final List<Duration> backoff;
    private final Duration lease;
    private final Clock clock;
    private final MeterRegistry meters;

    @Autowired
    public VerificationWorker(ProcessingClaims claims, WorkspaceRepository workspaces,
            VerificationRepository verifications, SellerUpserts sellers, CompanyDataProvider provider,
            ProviderBudgets budgets, ChecksEvaluator evaluator, VerdictEngine engine,
            MongoTransactionManager transactionManager, KybrProperties properties, Clock clock, MeterRegistry meters) {
        this(claims, workspaces, verifications, sellers, provider, budgets, evaluator, engine,
                transactionManager, properties.verification().maxAttempts(), properties.verification().backoff(),
                properties.verification().lease(), clock, meters);
    }

    VerificationWorker(ProcessingClaims claims, WorkspaceRepository workspaces,
            VerificationRepository verifications, SellerUpserts sellers, CompanyDataProvider provider,
            ProviderBudgets budgets, ChecksEvaluator evaluator, VerdictEngine engine,
            MongoTransactionManager transactionManager, int maxAttempts, List<Duration> backoff, Duration lease,
            Clock clock, MeterRegistry meters) {
        this.claims = claims;
        this.workspaces = workspaces;
        this.verifications = verifications;
        this.sellers = sellers;
        this.provider = provider;
        this.budgets = budgets;
        this.evaluator = evaluator;
        this.engine = engine;
        this.transactions = new TransactionTemplate(transactionManager);
        this.maxAttempts = maxAttempts;
        this.backoff = List.copyOf(backoff);
        this.lease = lease;
        this.clock = clock;
        this.meters = meters;
    }

    /** Claims and verifies this case if it's claimable now; false when it isn't. */
    public boolean process(UUID caseId) {
        String owner = UUID.randomUUID().toString();
        return claims.claim(caseId, clock.instant(), owner, lease).map(claimed -> run(claimed, owner)).isPresent();
    }

    /** Claims and verifies the case due earliest; false when none is due. */
    public boolean processNext() {
        String owner = UUID.randomUUID().toString();
        return claims.claimNext(clock.instant(), owner, lease).map(claimed -> run(claimed, owner)).isPresent();
    }

    /**
     * Never throws: an unexpected failure counts as an attempt and is retried, then goes to manual
     * review. If even that write fails, the claim expires and the next claim enforces the maximum.
     */
    private boolean run(LinkCase linkCase, String owner) {
        try {
            verify(linkCase, owner);
        } catch (RuntimeException e) {
            log.error("event=verification_error error={}", e.getClass().getSimpleName());
            try {
                retry(linkCase, owner, MotivoRevisao.FALHA_PROCESSAMENTO, Duration.ZERO);
            } catch (RuntimeException again) {
                log.error("event=verification_error error={}", again.getClass().getSimpleName());
            }
        }
        return true;
    }

    private void verify(LinkCase linkCase, String owner) {
        if (linkCase.processamento().tentativas() > maxAttempts) {
            failed(linkCase, owner, MotivoRevisao.FALHA_PROCESSAMENTO);
            return;
        }
        String slug = MetricTags.slug(workspaces.findById(linkCase.workspaceId()).orElse(null));
        Decision decision = budgets.canSpendD3(slug);
        if (!decision.allowed()) {
            meters.counter(CompanyDataResult.CALLS_METRIC, "tipo", "full_check", "resultado",
                    CompanyDataResult.BUDGET_DENIED).increment();
            defer(linkCase, owner, slug, decision);
            return;
        }
        Optional<Duration> wait = budgets.acquireProviderCall();
        if (wait.isPresent()) {
            claims.reschedule(linkCase.id(), owner, clock.instant().plus(wait.get()), true);
            return;
        }
        Instant consultadoEm = clock.instant();
        CompanyDataResult<CompanyRecord> result = provider.fullCheck(linkCase.cnpj());
        meters.counter(CompanyDataResult.CALLS_METRIC, "tipo", "full_check", "resultado", result.resultado())
                .increment();
        switch (result) {
            case CompanyDataResult.Found<CompanyRecord> found -> {
                budgets.bookD3(slug);
                CompanyRecord company = found.company();
                if (company.razaoSocial() == null || company.uf() == null || company.situacao() == null
                        || company.dataAbertura() == null) {
                    log.warn("event=provider_incomplete_record tipo=full_check");
                    failed(linkCase, owner, MotivoRevisao.DADOS_INCOMPLETOS);
                    return;
                }
                if (company.dataAbertura().isAfter(LocalDate.now(clock.withZone(SAO_PAULO)))) {
                    log.warn("event=provider_anomalous_record tipo=full_check campo=data_abertura");
                    failed(linkCase, owner, MotivoRevisao.DADOS_INCOMPLETOS);
                    return;
                }
                decide(linkCase, owner, company, consultadoEm);
            }
            case CompanyDataResult.NotFound<CompanyRecord> notFound -> {
                budgets.bookD3(slug);
                budgets.recordNoData(slug);
                meters.counter("kybr.verification.no_data", MetricTags.SLUG, slug).increment();
                Resultado variant = new Resultado(null, null, Variante.DADOS_NAO_ENCONTRADOS, null, null, null, null);
                if (claims.toManualReview(linkCase.id(), owner, null, Variante.DADOS_NAO_ENCONTRADOS, variant)) {
                    recordDuration(linkCase, "no_data");
                }
            }
            case CompanyDataResult.Unsupported<CompanyRecord> unsupported -> {
                log.warn("event=provider_unsupported_cnpj tipo=full_check");
                failed(linkCase, owner, MotivoRevisao.PROVEDOR_NAO_SUPORTA);
            }
            case CompanyDataResult.RateLimited<CompanyRecord> rateLimited ->
                    retry(linkCase, owner, MotivoRevisao.PROVEDOR_INDISPONIVEL, rateLimited.retryAfter());
            case CompanyDataResult.Unavailable<CompanyRecord> unavailable ->
                    retry(linkCase, owner, MotivoRevisao.PROVEDOR_INDISPONIVEL, Duration.ZERO);
        }
    }

    private void decide(LinkCase linkCase, String owner, CompanyRecord company, Instant consultadoEm) {
        ChecksEvaluator.Evaluation evaluation = evaluator.evaluate(company, linkCase.responsavel(), recentDays(linkCase));
        Verdict verdict = engine.decide(linkCase, company, evaluation, ModoConsulta.tempo_real, consultadoEm);
        try {
            TransactionRetry.DEFAULT.execute(() -> transactions.execute(status -> {
                verifications.insert(verdict.verification());
                if (!claims.complete(linkCase.id(), owner, verdict.estado(), verdict.resultado(),
                        verdict.verification().id(), verdict.prazoRevisao(),
                        verdict.revisaoSlaAte() == null ? null : new Revisao(verdict.revisaoSlaAte()))) {
                    throw new LeaseLostException();
                }
                updateSeller(linkCase, verdict);
                return null;
            }));
        } catch (LeaseLostException e) {
            log.info("event=verification_lease_lost");
            return;
        }
        recordDuration(linkCase, verdict.estado().name());
    }

    private void updateSeller(LinkCase linkCase, Verdict verdict) {
        sellers.recordVerdict(linkCase.workspaceId(), linkCase.cnpj(), verdict.resultado().razaoSocial(),
                verdict.statusSeller(), clock.instant(), verdict.verification().id());
    }

    /**
     * A failed attempt: back in the queue after the backoff (never sooner than {@code atLeast}), or
     * manual review with {@code motivo} once the attempts are spent.
     */
    private void retry(LinkCase linkCase, String owner, MotivoRevisao motivo, Duration atLeast) {
        int attempts = linkCase.processamento().tentativas();
        if (attempts >= maxAttempts) {
            failed(linkCase, owner, motivo);
            return;
        }
        Duration wait = backoff.get(Math.min(Math.max(attempts, 1), backoff.size()) - 1);
        claims.reschedule(linkCase.id(), owner, clock.instant().plus(wait.compareTo(atLeast) < 0 ? atLeast : wait),
                false);
    }

    private void failed(LinkCase linkCase, String owner, MotivoRevisao motivo) {
        if (claims.toManualReview(linkCase.id(), owner, motivo, null, null)) {
            meters.counter("kybr.verification.failed", "motivo", motivo.name()).increment();
            log.warn("event=verification_failed motivo={}", motivo);
            recordDuration(linkCase, "failed");
        }
    }

    private void defer(LinkCase linkCase, String owner, String slug, Decision decision) {
        MotivoRevisao motivo = deferral(decision);
        if (claims.toManualReview(linkCase.id(), owner, motivo, null, null)) {
            meters.counter("kybr.verification.deferred", "motivo", motivo.name()).increment();
            if (motivo == MotivoRevisao.LIMITE_SEM_DADOS) {
                meters.counter("kybr.verification.no_data_cap", MetricTags.SLUG, slug).increment();
            }
            log.warn("event=verification_deferred motivo={}", motivo);
            recordDuration(linkCase, "deferred");
        }
    }

    private static MotivoRevisao deferral(Decision decision) {
        if (decision.spendOff()) {
            return MotivoRevisao.GASTO_DESLIGADO;
        }
        Budget budget = decision.exhausted();
        if (budget == Budget.NO_DATA) {
            return MotivoRevisao.LIMITE_SEM_DADOS;
        }
        return budget == Budget.D3_DAILY || budget == Budget.D3_SLUG ? MotivoRevisao.ORCAMENTO_DIARIO
                : MotivoRevisao.ORCAMENTO;
    }

    private static int recentDays(LinkCase linkCase) {
        if (linkCase.politica() == null) {
            return DEFAULT_RECENT_DAYS;
        }
        return linkCase.politica().regras().stream()
                .filter(rule -> rule.chave() == Chave.recente && rule.dias() != null)
                .map(PoliticaRegra::dias)
                .findFirst()
                .orElse(DEFAULT_RECENT_DAYS);
    }

    /**
     * From the start's commit to the verdict, as a histogram (with a bucket at the 5 s target) so the
     * p90 alert reads real buckets.
     */
    private void recordDuration(LinkCase linkCase, String resultado) {
        Timer.builder("kybr.verification.duration").tag("resultado", resultado)
                .publishPercentileHistogram()
                .serviceLevelObjectives(DURATION_TARGET)
                .minimumExpectedValue(Duration.ofMillis(50))
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(meters)
                .record(Duration.between(linkCase.criadoEm(), clock.instant()));
    }

    /** The claim was taken over while the provider answered: abort the write. */
    private static final class LeaseLostException extends RuntimeException {
        LeaseLostException() {
            super(null, null, false, false);
        }
    }
}
