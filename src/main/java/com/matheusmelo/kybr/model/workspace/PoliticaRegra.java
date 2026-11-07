package com.matheusmelo.kybr.model.workspace;

import java.util.List;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One policy rule.
 *
 * @param chave the condition it covers
 * @param acao the action it takes, one of {@code acoesPermitidas}
 * @param acoesPermitidas the actions the panel may choose for this rule
 * @param dias the "recent company" threshold in days; set for {@link Chave#recente} only
 */
public record PoliticaRegra(@Field("chave") Chave chave, @Field("acao") Acao acao,
        @Field("acoes_permitidas") List<Acao> acoesPermitidas, @Field("dias") Integer dias) {

    public PoliticaRegra {
        acoesPermitidas = acoesPermitidas == null ? List.of() : List.copyOf(acoesPermitidas);
    }
}
