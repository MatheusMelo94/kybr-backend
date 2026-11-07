package com.matheusmelo.kybr.model.link;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A cached company lookup, keyed by canonical CNPJ: the projection, or a "not found" answer. The
 * TTL monitor removes it after {@code expiraEm}; freshness is still decided by the app clock.
 *
 * @param projecao set when the company was found
 * @param naoEncontrada true for a cached "not found"
 */
@Document(CompanyCacheEntry.COLLECTION)
public record CompanyCacheEntry(
        @Id String cnpj,
        @Field("projecao") ProjecaoEmpresa projecao,
        @Field("nao_encontrada") boolean naoEncontrada,
        @Field("consultado_em") Instant consultadoEm,
        @Field("expira_em") Instant expiraEm) {

    public static final String COLLECTION = "company_cache";
    public static final String EXPIRA_EM = "expira_em";

    public static CompanyCacheEntry found(String cnpj, ProjecaoEmpresa projecao, Instant consultadoEm,
            Instant expiraEm) {
        return new CompanyCacheEntry(cnpj, projecao, false, consultadoEm, expiraEm);
    }

    public static CompanyCacheEntry notFound(String cnpj, Instant consultadoEm, Instant expiraEm) {
        return new CompanyCacheEntry(cnpj, null, true, consultadoEm, expiraEm);
    }
}
