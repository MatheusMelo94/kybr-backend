package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DocumentRepository extends MongoRepository<Document, UUID> {

    List<Document> findByCaseId(UUID caseId);

    List<Document> findByCaseIdAndTipoOrderByPaginaAsc(UUID caseId, DocumentoTipo tipo);

    /** Whether this exact original file is already stored for the case and type (a retry). */
    boolean existsByCaseIdAndTipoAndSha256Original(UUID caseId, DocumentoTipo tipo, String sha256Original);

    /** The type's files with any of these original hashes, in any state (the duplicate guard). */
    List<Document> findByCaseIdAndTipoAndSha256OriginalIn(UUID caseId, DocumentoTipo tipo, List<String> sha256Originals);

    /** Whether a quarantined object belongs to a file still being verified (the orphan check). */
    boolean existsByQuarentenaKeyAndEstadoIn(String quarentenaKey, List<EstadoDocumento> estados);

    /** Whether a stored object has its record (the orphan check). */
    boolean existsByStorageKey(String storageKey);

    long deleteByCaseId(UUID caseId);
}
