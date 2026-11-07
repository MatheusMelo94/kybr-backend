package com.matheusmelo.kybr.model.link;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A company known to a workspace: one per (workspace, CNPJ).
 *
 * @param razaoSocial CPF-masked; provider text, untrusted on output
 * @param ultimaVerificacaoId the most recent verification
 */
@Document(Seller.COLLECTION)
public record Seller(
        @Id UUID id,
        @Field("workspace_id") UUID workspaceId,
        @Field("cnpj") String cnpj,
        @Field("origem") OrigemSeller origem,
        @Field("razao_social") String razaoSocial,
        @Field("status") StatusSeller status,
        @Field("verificado_em") Instant verificadoEm,
        @Field("ultima_verificacao_id") UUID ultimaVerificacaoId) {

    public static final String COLLECTION = "sellers";
    public static final String WORKSPACE_ID = "workspace_id";
    public static final String CNPJ = "cnpj";
}
