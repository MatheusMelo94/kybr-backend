package com.matheusmelo.kybr.repository;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.DocumentItemState;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.EstadoRetencao;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The case-side writes of an upload and of the scan job, each one conditional update on the case:
 * it lands only while the case token is live and retention hasn't started. Retention revokes the
 * token in its first write, so a conditional write here and retention's claim conflict and
 * serialise inside their transactions; checking the same thing with a read wouldn't. A custom
 * component is justified by the layer rules (conditional updates with array filters).
 *
 * <p>Every write also sets {@link LinkCase#DOCUMENTOS_ATUALIZADO_EM}, so even a case without a D4
 * item for the type is really written (an update that changes nothing conflicts with nothing).
 */
@Component
public class CaseDocumentWrites {

    private final MongoTemplate mongo;

    public CaseDocumentWrites(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** The type's files, oldest first: the input of its D4 item state. */
    public List<Document> files(UUID caseId, DocumentoTipo tipo) {
        return mongo.find(query(where(Document.CASE_ID).is(caseId).and(Document.TIPO).is(tipo))
                .with(Sort.by(Document.CRIADO_EM, Document.PAGINA)), Document.class);
    }

    /**
     * Records the type's item state on the case.
     *
     * @param decidedRefused whether a decided (approved or blocked) case refuses the write too
     * @return false when the case is gone, its token revoked or its retention started (or, with
     *     {@code decidedRefused}, decided): nothing was written
     */
    public boolean recordItem(UUID caseId, DocumentoTipo tipo, DocumentItemState state, Instant now,
            boolean decidedRefused) {
        return write(caseId, tipo, state, now, decidedRefused, new Update());
    }

    /**
     * Gives a refused file's share of the reservation back and records the type's item state; a type
     * left with no file holds no format again.
     *
     * @return false when the case is gone, its token revoked or its retention started
     */
    public boolean releaseAndRecord(UUID caseId, DocumentoTipo tipo, long bytes, DocumentItemState state,
            Instant now) {
        Update release = new Update()
                .inc(LinkCase.documentosField(tipo, DocumentosTipo.BYTES), -bytes)
                .inc(LinkCase.documentosField(tipo, DocumentosTipo.ARQUIVOS), -1)
                .inc(LinkCase.ARQUIVOS_TOTAL, -1);
        if (!write(caseId, tipo, state, now, false, release)) {
            return false;
        }
        mongo.updateFirst(query(where("_id").is(caseId)
                        .and(LinkCase.documentosField(tipo, DocumentosTipo.ARQUIVOS)).lte(0)),
                new Update().unset(LinkCase.DOCUMENTOS + "." + tipo.name()), LinkCase.class);
        return true;
    }

    private boolean write(UUID caseId, DocumentoTipo tipo, DocumentItemState state, Instant now,
            boolean decidedRefused, Update update) {
        Criteria live = where("_id").is(caseId)
                .and(LinkCase.TOKEN_REVOGADO_EM).is(null)
                .and(LinkCase.RETENCAO_ESTADO).nin(EstadoRetencao.EM_ANDAMENTO.name(), EstadoRetencao.CONCLUIDA.name());
        if (decidedRefused) {
            live = live.and(LinkCase.ESTADO).nin(EstadoCaso.approved.name(), EstadoCaso.blocked.name());
        }
        update.set(LinkCase.DOCUMENTOS_ATUALIZADO_EM, now);
        if (hasItem(caseId, tipo)) {
            String item = LinkCase.RESULTADO + ".itens.$[item].";
            update.set(item + ItemResultado.DOCUMENTOS_ENVIADOS, state.enviados())
                    .set(item + ItemResultado.SITUACAO_ENVIO, state.situacao().name())
                    .set(item + ItemResultado.DOCUMENTOS_RECUSADOS, state.recusados());
            setOrUnset(update, item + ItemResultado.FORMATO_ENVIO, state.formato() == null ? null : state.formato().name());
            setOrUnset(update, item + ItemResultado.MOTIVO_RECUSA, state.motivo() == null ? null : state.motivo().name());
            update.filterArray(where("item." + ItemResultado.DOCUMENTO_TIPO).is(tipo.name()));
        }
        return mongo.updateFirst(query(live), update, LinkCase.class).getMatchedCount() == 1;
    }

    private boolean hasItem(UUID caseId, DocumentoTipo tipo) {
        return mongo.exists(query(where("_id").is(caseId)
                .and(LinkCase.RESULTADO + ".itens." + ItemResultado.DOCUMENTO_TIPO).is(tipo.name())), LinkCase.class);
    }

    private static void setOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }
}
