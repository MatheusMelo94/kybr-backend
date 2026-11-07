package com.matheusmelo.kybr.service.company;

import com.matheusmelo.kybr.model.link.ProjecaoEmpresa;
import com.matheusmelo.kybr.model.workspace.Workspace;
import java.util.Objects;

/**
 * The company lookup behind a seller link: the kill switches, the local cache, the cache-only
 * modes and budgets, then the provider. The caller resolves the workspace, validates the CNPJ and
 * applies the request limits, and records the lookup metric for the outcomes it decides itself
 * (an invalid CNPJ); this service records every other outcome.
 */
public interface CompanyLookupService {

    /**
     * False when autofill is off globally or for this workspace. Checked first, so a switched-off
     * lookup answers "disabled" even for an invalid CNPJ.
     */
    boolean autofillEnabled(Workspace workspace);

    /** Looks up a canonical, valid CNPJ for {@code workspace}. Never throws for a provider failure. */
    Result lookup(Workspace workspace, String cnpj);

    sealed interface Result {
    }

    /** The company, CPF-masked. */
    record Found(ProjecaoEmpresa projecao) implements Result {
        public Found {
            Objects.requireNonNull(projecao, "projecao");
        }
    }

    /** Neither the cache nor the provider knows the company. */
    record NotFound() implements Result {
    }

    /** Autofill was switched off (globally or for this workspace). */
    record Disabled() implements Result {
    }

    /** Not cached, and this lookup may not call the provider (trial plan, a spent budget, spend off). */
    record CacheOnlyMiss() implements Result {
    }

    /** The provider failed, timed out, can't handle the CNPJ or returned an incomplete record. */
    record Unavailable() implements Result {
    }
}
