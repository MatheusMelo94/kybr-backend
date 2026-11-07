package com.matheusmelo.kybr.model.workspace;

import org.springframework.data.mongodb.core.mapping.Field;

/** The workspace's plan. A trial ({@code teste}) workspace gets cached company lookups only. */
public record Plano(@Field("tipo") Tipo tipo) {

    public enum Tipo {
        interno,
        teste,
        pago
    }
}
