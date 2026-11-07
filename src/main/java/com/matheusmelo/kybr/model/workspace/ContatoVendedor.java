package com.matheusmelo.kybr.model.workspace;

import org.springframework.data.mongodb.core.mapping.Field;

/**
 * How a seller reaches the workspace: a plain e-mail address or an absolute {@code https:} URL,
 * validated when the workspace is provisioned.
 */
public record ContatoVendedor(@Field("tipo") Tipo tipo, @Field("valor") String valor) {

    public enum Tipo {
        email,
        url
    }
}
