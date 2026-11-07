package com.matheusmelo.kybr.model.link;

import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One line of a case result: a confirmation, a pending item or the blocking reason. Only the
 * fields its {@code codigo} needs are set.
 *
 * @param situacao with {@code SITUACAO_IRREGULAR}
 * @param urlRegularizacao an {@code https:} gov.br page, with an irregular-status code
 * @param dias with {@code EMPRESA_RECENTE}, at least 1
 * @param uf with {@code IE_ATIVA}
 * @param documentoTipo with a document code
 * @param documentosEnviados with a document code
 */
public record ItemResultado(
        @Field("codigo") CodigoResultado codigo,
        @Field("situacao") SituacaoCadastral situacao,
        @Field("url_regularizacao") String urlRegularizacao,
        @Field("dias") Integer dias,
        @Field("uf") String uf,
        @Field("documento_tipo") DocumentoTipo documentoTipo,
        @Field("documentos_enviados") Integer documentosEnviados) {

    /** An item that needs nothing but its code. */
    public static ItemResultado of(CodigoResultado codigo) {
        return new ItemResultado(codigo, null, null, null, null, null, null);
    }
}
