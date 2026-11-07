package com.matheusmelo.kybr.dto.link;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * E02: what the seller sees to confirm the company. Never a street address and never a CNAE code.
 *
 * @param razaoSocial the company name, any CPF masked
 * @param municipio the town of the company's address
 * @param uf the state, two upper-case letters
 * @param cnae the main activity
 */
public record LookupResponse(
        @Schema(description = "The company name, any CPF masked as `***.***.***-**`.", minLength = 1,
                example = "LOJA EXEMPLO COMERCIO LTDA", requiredMode = Schema.RequiredMode.REQUIRED)
        String razaoSocial,
        @Schema(description = "The town of the company's address.", minLength = 1, example = "Sao Paulo",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String municipio,
        @Schema(description = "The state.", pattern = "^[A-Z]{2}$", example = "SP",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String uf,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Cnae cnae) {

    /** @param descricao the main activity's description */
    @Schema(description = "The company's main activity: the description only, never the code.")
    public record Cnae(
            @Schema(description = "The main activity's description.", minLength = 1,
                    example = "Comercio varejista de artigos do vestuario", requiredMode = Schema.RequiredMode.REQUIRED)
            String descricao) {
    }
}
