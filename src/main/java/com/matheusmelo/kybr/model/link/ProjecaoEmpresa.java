package com.matheusmelo.kybr.model.link;

import org.springframework.data.mongodb.core.mapping.Field;

/**
 * The company-lookup projection: what the seller sees to confirm the company. Never a street
 * address and never a CNAE code.
 *
 * @param razaoSocial CPF-masked when stored
 */
public record ProjecaoEmpresa(
        @Field("razao_social") String razaoSocial,
        @Field("municipio") String municipio,
        @Field("uf") String uf,
        @Field("cnae_descricao") String cnaeDescricao) {
}
