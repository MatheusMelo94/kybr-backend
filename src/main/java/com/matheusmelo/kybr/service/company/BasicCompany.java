package com.matheusmelo.kybr.service.company;

/**
 * The provider's basic record: what a company lookup shows the seller. Any field may be missing
 * from the provider's answer (null); a record missing one isn't shown or cached. Values are as the
 * provider sent them: the CPF in an individual's company name isn't masked yet.
 */
public record BasicCompany(String razaoSocial, String municipio, String uf, String cnaeDescricao) {
}
