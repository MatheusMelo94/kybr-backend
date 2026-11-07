package com.matheusmelo.kybr.service.company;

/**
 * The company-data provider port. Implementations: a stub that replays synthetic fixtures, and the
 * live provider client. Every outcome is a {@link CompanyDataResult}; implementations never throw
 * for a provider failure and never log a response body.
 *
 * <p>Callers pass a canonical, valid CNPJ.
 */
public interface CompanyDataProvider {

    /** The basic record a company lookup shows. */
    CompanyDataResult<BasicCompany> basic(String cnpj);

    /** The full record a verification needs: base data, Simples/MEI, state registrations, partners. */
    CompanyDataResult<CompanyRecord> fullCheck(String cnpj);
}
