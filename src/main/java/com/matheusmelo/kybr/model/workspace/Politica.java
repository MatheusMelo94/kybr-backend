package com.matheusmelo.kybr.model.workspace;

import java.util.List;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A workspace's verification policy. {@code versao} increases on every change; a case keeps a copy
 * of the policy it was created under, so a change is never retroactive.
 */
public record Politica(@Field("versao") int versao, @Field("regras") List<PoliticaRegra> regras) {

    public Politica {
        regras = regras == null ? List.of() : List.copyOf(regras);
    }
}
