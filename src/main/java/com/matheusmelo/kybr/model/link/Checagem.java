package com.matheusmelo.kybr.model.link;

import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One check's outcome, as the panel shows it. {@code titulo}, {@code descricao} and {@code valor}
 * are fixed or derived text, never a partner's name.
 */
public record Checagem(
        @Field("chave") ChaveChecagem chave,
        @Field("titulo") String titulo,
        @Field("descricao") String descricao,
        @Field("valor") String valor,
        @Field("status") StatusChecagem status) {
}
