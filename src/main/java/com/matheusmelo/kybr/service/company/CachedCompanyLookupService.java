package com.matheusmelo.kybr.service.company;

import com.matheusmelo.kybr.model.link.ProjecaoEmpresa;
import com.matheusmelo.kybr.model.workspace.Plano;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.MetricTags;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The company lookup behind a seller link, in order: the kill switches; the local cache (free); the
 * cache-only modes (a trial workspace, a spent budget, provider spend off, the provider's per-minute
 * gate full, shared with the verifications); then the provider's basic record. A found company is cached CPF-masked and its credits are booked; a provider "not
 * found" is cached negatively. Failures, an unsupported CNPJ and an incomplete record answer
 * "unavailable" and cache nothing.
 *
 * <p>Every outcome counts {@code kybr.link.lookup{slug, resultado}} with the loaded workspace's
 * slug only. The CNPJ is never logged.
 */
@Service
public class CachedCompanyLookupService implements CompanyLookupService {

    private static final Logger log = LoggerFactory.getLogger(CachedCompanyLookupService.class);

    private final RuntimeFlagsService flags;
    private final CompanyCache cache;
    private final ProviderBudgets budgets;
    private final CompanyDataProvider provider;
    private final MeterRegistry meters;

    public CachedCompanyLookupService(RuntimeFlagsService flags, CompanyCache cache, ProviderBudgets budgets,
            CompanyDataProvider provider, MeterRegistry meters) {
        this.flags = flags;
        this.cache = cache;
        this.budgets = budgets;
        this.provider = provider;
        this.meters = meters;
    }

    @Override
    public boolean autofillEnabled(Workspace workspace) {
        return workspace.autofillAtivo() && flags.autofillOn();
    }

    @Override
    public Result lookup(Workspace workspace, String cnpj) {
        String slug = MetricTags.slug(workspace);
        Outcome outcome = decide(workspace, slug, cnpj);
        meters.counter("kybr.link.lookup", MetricTags.SLUG, slug, "resultado", outcome.resultado()).increment();
        return outcome.result();
    }

    /** A result and its {@code resultado} metric tag. */
    private record Outcome(Result result, String resultado) {
    }

    private Outcome decide(Workspace workspace, String slug, String cnpj) {
        if (!autofillEnabled(workspace)) {
            return new Outcome(new Disabled(), "disabled");
        }
        switch (cache.read(cnpj)) {
            case CompanyCache.Lookup.Hit hit -> {
                cacheRead(slug);
                return new Outcome(new Found(hit.projecao()), "hit");
            }
            case CompanyCache.Lookup.NegativeHit negative -> {
                cacheRead(slug);
                return new Outcome(new NotFound(), "not_found");
            }
            case CompanyCache.Lookup.Miss miss -> {
                // fall through to the provider
            }
        }
        if (workspace.plano() != null && workspace.plano().tipo() == Plano.Tipo.teste) {
            return new Outcome(new CacheOnlyMiss(), "cache_only_miss");
        }
        if (!budgets.canSpendD2(slug).allowed()) {
            meters.counter(CompanyDataResult.CALLS_METRIC, "tipo", "basic", "resultado",
                    CompanyDataResult.BUDGET_DENIED).increment();
            return new Outcome(new CacheOnlyMiss(), "cache_only_miss");
        }
        if (budgets.acquireProviderCall().isPresent()) {
            return new Outcome(new CacheOnlyMiss(), "cache_only_miss");
        }
        CompanyDataResult<BasicCompany> result = provider.basic(cnpj);
        meters.counter(CompanyDataResult.CALLS_METRIC, "tipo", "basic", "resultado", result.resultado()).increment();
        return switch (result) {
            case CompanyDataResult.Found<BasicCompany> found -> {
                budgets.bookD2(slug);
                Optional<ProjecaoEmpresa> stored = cache.storeFound(cnpj, found.company());
                if (stored.isEmpty()) {
                    log.warn("event=provider_incomplete_record tipo=basic");
                    yield new Outcome(new Unavailable(), "error");
                }
                yield new Outcome(new Found(stored.get()), "fresh");
            }
            case CompanyDataResult.NotFound<BasicCompany> notFound -> {
                budgets.bookD2(slug);
                cache.storeNotFound(cnpj);
                yield new Outcome(new NotFound(), "not_found");
            }
            case CompanyDataResult.Unsupported<BasicCompany> unsupported -> {
                log.warn("event=provider_unsupported_cnpj tipo=basic");
                yield new Outcome(new Unavailable(), "error");
            }
            case CompanyDataResult.RateLimited<BasicCompany> rateLimited -> new Outcome(new Unavailable(), "error");
            case CompanyDataResult.Unavailable<BasicCompany> unavailable -> new Outcome(new Unavailable(), "error");
        };
    }

    private void cacheRead(String slug) {
        meters.counter("kybr.link.lookup.cache_reads", MetricTags.SLUG, slug).increment();
        budgets.recordCacheRead(slug);
    }
}
