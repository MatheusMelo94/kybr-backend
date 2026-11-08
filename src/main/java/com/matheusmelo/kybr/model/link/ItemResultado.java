package com.matheusmelo.kybr.model.link;

import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.document.MotivoRecusa;
import com.matheusmelo.kybr.model.document.SituacaoEnvio;
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
 * @param documentosEnviados with a document code: the type's files accepted or still being verified
 * @param formatoEnvio with a document code, once a file was sent for its type: how it was sent
 * @param situacaoEnvio with a document code, once a file was sent for its type
 * @param documentosRecusados with a document code, once a file was sent for its type: files refused
 * @param motivoRecusa with {@code situacaoEnvio=recusado}: why the latest upload's file was refused
 */
public record ItemResultado(
        @Field("codigo") CodigoResultado codigo,
        @Field("situacao") SituacaoCadastral situacao,
        @Field("url_regularizacao") String urlRegularizacao,
        @Field("dias") Integer dias,
        @Field("uf") String uf,
        @Field("documento_tipo") DocumentoTipo documentoTipo,
        @Field("documentos_enviados") Integer documentosEnviados,
        @Field("formato_envio") FormatoEnvio formatoEnvio,
        @Field("situacao_envio") SituacaoEnvio situacaoEnvio,
        @Field("documentos_recusados") Integer documentosRecusados,
        @Field("motivo_recusa") MotivoRecusa motivoRecusa) {

    public static final String DOCUMENTO_TIPO = "documento_tipo";
    public static final String DOCUMENTOS_ENVIADOS = "documentos_enviados";
    public static final String FORMATO_ENVIO = "formato_envio";
    public static final String SITUACAO_ENVIO = "situacao_envio";
    public static final String DOCUMENTOS_RECUSADOS = "documentos_recusados";
    public static final String MOTIVO_RECUSA = "motivo_recusa";

    /** An item with an upload format and no verification state (not one written by an upload). */
    public ItemResultado(CodigoResultado codigo, SituacaoCadastral situacao, String urlRegularizacao, Integer dias,
            String uf, DocumentoTipo documentoTipo, Integer documentosEnviados, FormatoEnvio formatoEnvio) {
        this(codigo, situacao, urlRegularizacao, dias, uf, documentoTipo, documentosEnviados, formatoEnvio, null, null,
                null);
    }

    /** An item without an upload format (nothing sent yet, or not a document item). */
    public ItemResultado(CodigoResultado codigo, SituacaoCadastral situacao, String urlRegularizacao, Integer dias,
            String uf, DocumentoTipo documentoTipo, Integer documentosEnviados) {
        this(codigo, situacao, urlRegularizacao, dias, uf, documentoTipo, documentosEnviados, null);
    }

    /** An item that needs nothing but its code. */
    public static ItemResultado of(CodigoResultado codigo) {
        return new ItemResultado(codigo, null, null, null, null, null, null);
    }
}
