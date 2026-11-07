package com.matheusmelo.kybr.model.link;

import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * The seller-facing projection of a verdict, stored on the case when the verdict is written, so a
 * case read never re-derives it. It holds no raw check, partner name, policy name or verdict word.
 * The responsible person's name and the masked contact e-mail come from the case itself.
 *
 * @param razaoSocial CPF-masked; absent with {@code variante}
 * @param consultadoEm when the provider data was fetched; absent with {@code variante}
 * @param prazoRevisao set when pending
 * @param confirmacoes set when approved
 * @param itens set when pending
 * @param motivo set when blocked
 */
public record Resultado(
        @Field("razao_social") String razaoSocial,
        @Field("consultado_em") Instant consultadoEm,
        @Field("variante") Variante variante,
        @Field("prazo_revisao") Instant prazoRevisao,
        @Field("confirmacoes") List<ItemResultado> confirmacoes,
        @Field("itens") List<ItemResultado> itens,
        @Field("motivo") ItemResultado motivo) {

    public Resultado {
        confirmacoes = confirmacoes == null ? null : List.copyOf(confirmacoes);
        itens = itens == null ? null : List.copyOf(itens);
    }
}
