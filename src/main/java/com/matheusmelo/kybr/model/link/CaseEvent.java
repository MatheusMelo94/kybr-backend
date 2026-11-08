package com.matheusmelo.kybr.model.link;

import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** One entry on a case's timeline. Only inserted. */
@Document(CaseEvent.COLLECTION)
public record CaseEvent(
        @Id UUID id,
        @Field("case_id") UUID caseId,
        @Field("tipo") TipoEventoCaso tipo,
        @Field("dados") Dados dados,
        @Field("ocorrido_em") Instant ocorridoEm) {

    public static final String COLLECTION = "case_events";
    public static final String CASE_ID = "case_id";
    public static final String OCORRIDO_EM = "ocorrido_em";

    /** @param tipo the document type, with {@code documento_recebido} */
    public record Dados(@Field("tipo") DocumentoTipo tipo) {
    }
}
