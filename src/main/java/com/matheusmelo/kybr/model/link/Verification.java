package com.matheusmelo.kybr.model.link;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One verification of a case: the checks, the verdict and the minimal company snapshot behind it.
 *
 * @param motivos fixed pt-BR reasons per rule, never seller text
 * @param politicaVersao the version of the case's policy copy the verdict used
 * @param aprovadoComLimite an approval reached through an "approve with limit" rule
 */
@Document(Verification.COLLECTION)
public record Verification(
        @Id UUID id,
        @Field("workspace_id") UUID workspaceId,
        @Field("case_id") UUID caseId,
        @Field("cnpj") String cnpj,
        @Field("modo") ModoConsulta modo,
        @Field("consultado_em") Instant consultadoEm,
        @Field("snapshot") SnapshotEmpresa snapshot,
        @Field("checagens") List<Checagem> checagens,
        @Field("veredito") Veredito veredito,
        @Field("status") StatusChecagem status,
        @Field("motivos") List<String> motivos,
        @Field("politica_versao") int politicaVersao,
        @Field("aprovado_com_limite") boolean aprovadoComLimite,
        @Field("criado_em") Instant criadoEm) {

    public static final String COLLECTION = "verifications";
    public static final String WORKSPACE_ID = "workspace_id";
    public static final String CASE_ID = "case_id";

    public Verification {
        checagens = checagens == null ? List.of() : List.copyOf(checagens);
        motivos = motivos == null ? List.of() : List.copyOf(motivos);
    }
}
