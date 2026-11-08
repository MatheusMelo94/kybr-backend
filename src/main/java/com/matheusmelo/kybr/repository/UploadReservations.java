package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Reserves an upload's room on its case before any file is scanned or stored: the original bytes
 * per document type, the files per case and the type's format, all in one conditional update, so
 * two concurrent uploads can never together pass a limit. A failed upload gives the room back with
 * {@link #release}. Plain conditional updates only (MongoDB 7.0).
 */
@Component
public class UploadReservations {

    /** What a reservation came to; every value but {@link #RESERVED} changed nothing. */
    public enum Result {
        RESERVED,
        /** The type's original bytes would pass the limit. */
        ARQUIVO_GRANDE,
        /** The case's files would pass the limit. */
        LIMITE_ARQUIVOS,
        /** The type now holds a format this upload can't join. */
        FORMATO,
        /** The case is gone or its token was revoked. */
        CASO_NAO_ENCONTRADO
    }

    /** Conditional updates retried when the case changed between the update and the diagnosis. */
    private static final int ATTEMPTS = 3;

    private final MongoTemplate mongo;
    private final long maxBytesPerTipo;
    private final int maxFilesPerCase;

    @Autowired
    public UploadReservations(MongoTemplate mongo, KybrProperties properties) {
        this(mongo, properties.upload().maxBytesPerTipo().toBytes(), properties.upload().maxFilesPerCase());
    }

    UploadReservations(MongoTemplate mongo, long maxBytesPerTipo, int maxFilesPerCase) {
        this.mongo = mongo;
        this.maxBytesPerTipo = maxBytesPerTipo;
        this.maxFilesPerCase = maxFilesPerCase;
    }

    /**
     * @param formato the type's format after this upload
     * @param bytes the upload's original bytes
     * @param files the upload's files
     * @throws OptimisticLockingFailureException when the case kept changing under the reservation
     *     (a transient condition: the caller answers "retry later")
     */
    public Result reserve(UUID caseId, DocumentoTipo tipo, FormatoEnvio formato, long bytes, int files) {
        if (bytes > maxBytesPerTipo) {
            return Result.ARQUIVO_GRANDE;
        }
        if (files > maxFilesPerCase) {
            return Result.LIMITE_ARQUIVOS;
        }
        Update update = new Update()
                .inc(LinkCase.documentosField(tipo, DocumentosTipo.BYTES), bytes)
                .inc(LinkCase.documentosField(tipo, DocumentosTipo.ARQUIVOS), files)
                .inc(LinkCase.ARQUIVOS_TOTAL, files)
                .set(LinkCase.documentosField(tipo, DocumentosTipo.FORMATO), formato);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Query query = Query.query(fits(caseId, tipo, formato, bytes, files));
            if (mongo.updateFirst(query, update, LinkCase.class).getMatchedCount() == 1) {
                return Result.RESERVED;
            }
            Result refusal = diagnose(caseId, tipo, formato, bytes, files);
            if (refusal != Result.RESERVED) {
                return refusal;
            }
        }
        throw new OptimisticLockingFailureException("the case kept changing during the reservation");
    }

    /** Gives back a reservation's room; a type left with no files holds no format again. */
    public void release(UUID caseId, DocumentoTipo tipo, long bytes, int files) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(caseId)), new Update()
                .inc(LinkCase.documentosField(tipo, DocumentosTipo.BYTES), -bytes)
                .inc(LinkCase.documentosField(tipo, DocumentosTipo.ARQUIVOS), -files)
                .inc(LinkCase.ARQUIVOS_TOTAL, -files), LinkCase.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(caseId)
                .and(LinkCase.documentosField(tipo, DocumentosTipo.ARQUIVOS)).lte(0)),
                new Update().unset(LinkCase.DOCUMENTOS + "." + tipo.name()), LinkCase.class);
    }

    private Criteria fits(UUID caseId, DocumentoTipo tipo, FormatoEnvio formato, long bytes, int files) {
        Criteria criteria = Criteria.where("_id").is(caseId)
                .and(LinkCase.TOKEN_REVOGADO_EM).is(null)
                .and(LinkCase.documentosField(tipo, DocumentosTipo.BYTES)).not().gt(maxBytesPerTipo - bytes)
                .and(LinkCase.ARQUIVOS_TOTAL).not().gt(maxFilesPerCase - files);
        return formato == FormatoEnvio.pdf
                ? criteria.and(LinkCase.documentosField(tipo, DocumentosTipo.ARQUIVOS)).not().gt(0)
                : criteria.and(LinkCase.documentosField(tipo, DocumentosTipo.FORMATO)).ne(FormatoEnvio.pdf);
    }

    /** Why the case refused the reservation, or {@link Result#RESERVED} when it would fit now. */
    private Result diagnose(UUID caseId, DocumentoTipo tipo, FormatoEnvio formato, long bytes, int files) {
        LinkCase linkCase = mongo.findById(caseId, LinkCase.class);
        if (linkCase == null || linkCase.tokenRevogadoEm() != null) {
            return Result.CASO_NAO_ENCONTRADO;
        }
        DocumentosTipo held = linkCase.documentos().get(tipo);
        if (held != null && (formato == FormatoEnvio.pdf ? held.arquivos() > 0 : held.formato() == FormatoEnvio.pdf)) {
            return Result.FORMATO;
        }
        if ((held == null ? 0 : held.bytes()) + bytes > maxBytesPerTipo) {
            return Result.ARQUIVO_GRANDE;
        }
        if (linkCase.arquivosTotal() + files > maxFilesPerCase) {
            return Result.LIMITE_ARQUIVOS;
        }
        return Result.RESERVED;
    }
}
