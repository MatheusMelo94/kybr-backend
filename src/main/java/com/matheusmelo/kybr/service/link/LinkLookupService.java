package com.matheusmelo.kybr.service.link;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.InvalidParameterException;
import com.matheusmelo.kybr.exception.ParameterLocation;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.link.ProjecaoEmpresa;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.MetricTags;
import com.matheusmelo.kybr.service.company.CompanyLookupService;
import com.matheusmelo.kybr.util.Cnpj;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * The company lookup on a seller link. Checks run in this order: the autofill kill switches (503
 * {@code AUTOFILL_DESATIVADO}, even for an invalid CNPJ), the CNPJ (400 {@code CNPJ_INVALIDO} on
 * {@code cnpj}, also when missing), the request limits (429 {@code LIMITE_CONSULTAS}), then
 * {@link CompanyLookupService}. A trial link or a spent budget answers a cache miss with 503
 * {@code AUTOFILL_DESATIVADO}; a provider failure is 503 {@code INDISPONIVEL}.
 *
 * <p>{@code kybr.link.lookup} counts the two outcomes decided here ({@code disabled},
 * {@code invalid}); the company lookup counts its own. The CNPJ is never logged.
 */
@Service
public class LinkLookupService {

    static final String METRIC = "kybr.link.lookup";
    private static final String FIELD = "cnpj";

    private final CompanyLookupService companies;
    private final LinkRateLimits limits;
    private final MeterRegistry meters;

    public LinkLookupService(CompanyLookupService companies, LinkRateLimits limits, MeterRegistry meters) {
        this.companies = companies;
        this.limits = limits;
        this.meters = meters;
    }

    public ProjecaoEmpresa lookup(Workspace workspace, ClientIp clientIp, String cnpj) {
        if (!companies.autofillEnabled(workspace)) {
            count(workspace, "disabled");
            throw new ApiException(ErrorCode.AUTOFILL_DESATIVADO);
        }
        if (!Cnpj.isValidCanonical(cnpj)) {
            count(workspace, "invalid");
            throw new InvalidParameterException(ErrorCode.CNPJ_INVALIDO, FIELD, ParameterLocation.QUERY);
        }
        limits.lookup(clientIp, workspace);
        return switch (companies.lookup(workspace, cnpj)) {
            case CompanyLookupService.Found found -> found.projecao();
            case CompanyLookupService.NotFound notFound -> throw new ApiException(ErrorCode.EMPRESA_NAO_ENCONTRADA);
            case CompanyLookupService.Disabled disabled -> throw new ApiException(ErrorCode.AUTOFILL_DESATIVADO);
            case CompanyLookupService.CacheOnlyMiss miss -> throw new ApiException(ErrorCode.AUTOFILL_DESATIVADO);
            case CompanyLookupService.Unavailable unavailable -> throw new ApiException(ErrorCode.INDISPONIVEL);
        };
    }

    private void count(Workspace workspace, String resultado) {
        meters.counter(METRIC, MetricTags.SLUG, MetricTags.slug(workspace), "resultado", resultado).increment();
    }
}
