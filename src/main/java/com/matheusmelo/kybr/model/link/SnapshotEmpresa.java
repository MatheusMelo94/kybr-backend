package com.matheusmelo.kybr.model.link;

import java.time.LocalDate;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * The minimum company data a verification keeps. Partner (QSA) names are never stored: they are
 * matched in memory and dropped, so this type has no field that could hold one.
 *
 * @param razaoSocial CPF-masked
 */
public record SnapshotEmpresa(
        @Field("razao_social") String razaoSocial,
        @Field("municipio") String municipio,
        @Field("uf") String uf,
        @Field("cnae_codigo") String cnaeCodigo,
        @Field("cnae_descricao") String cnaeDescricao,
        @Field("situacao") SituacaoCadastral situacao,
        @Field("data_abertura") LocalDate dataAbertura,
        @Field("regime") Regime regime) {
}
