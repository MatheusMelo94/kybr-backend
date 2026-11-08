package com.matheusmelo.kybr.model.link;

import com.matheusmelo.kybr.model.document.FormatoEnvio;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * What a case holds for one document type, kept on the case so a limit check is one conditional
 * update.
 *
 * @param formato how the files were sent
 * @param arquivos files stored
 * @param bytes original bytes stored (what the seller sent), the unit of the per-type limit
 */
public record DocumentosTipo(
        @Field("formato") FormatoEnvio formato,
        @Field("arquivos") int arquivos,
        @Field("bytes") long bytes) {

    public static final String FORMATO = "formato";
    public static final String ARQUIVOS = "arquivos";
    public static final String BYTES = "bytes";
}
