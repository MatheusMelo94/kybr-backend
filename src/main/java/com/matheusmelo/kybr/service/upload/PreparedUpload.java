package com.matheusmelo.kybr.service.upload;

import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.repository.UploadReservations;
import java.util.List;

/**
 * An upload that passed every check up to its reservation: the new files (duplicates removed),
 * hashed and typed, with room reserved on the case and a concurrency permit held.
 *
 * <p>Always closed, whatever happens next: closing deletes every part's temporary file, releases
 * the permit, and gives the reservation back unless the upload was {@link #committed}.
 */
public final class PreparedUpload implements AutoCloseable {

    /**
     * One new file.
     *
     * @param part the part on disk
     * @param kind its type, from its first bytes
     * @param sha256Original lower-case hex SHA-256 of the bytes as sent
     * @param size the bytes as sent
     */
    public record IncomingFile(UploadPart part, MagicBytes.Kind kind, String sha256Original, long size) {
    }

    private final UploadCapability capability;
    private final DocumentoTipo tipo;
    private final FormatoEnvio formato;
    private final List<IncomingFile> files;
    private final int documentosEnviados;
    private final List<UploadPart> parts;
    private final UploadConcurrency.Permit permit;
    private final UploadReservations reservations;
    private boolean committed;
    private boolean closed;

    PreparedUpload(UploadCapability capability, DocumentoTipo tipo, FormatoEnvio formato, List<IncomingFile> files,
            int documentosEnviados, List<UploadPart> parts, UploadConcurrency.Permit permit,
            UploadReservations reservations) {
        this.capability = capability;
        this.tipo = tipo;
        this.formato = formato;
        this.files = List.copyOf(files);
        this.documentosEnviados = documentosEnviados;
        this.parts = List.copyOf(parts);
        this.permit = permit;
        this.reservations = reservations;
    }

    public UploadCapability capability() {
        return capability;
    }

    public DocumentoTipo tipo() {
        return tipo;
    }

    /** The type's format after this upload. */
    public FormatoEnvio formato() {
        return formato;
    }

    /** The new files, in request order; empty when every file was already stored (a retry). */
    public List<IncomingFile> files() {
        return files;
    }

    /** The original bytes of the new files: what was reserved. */
    public long bytes() {
        return files.stream().mapToLong(IncomingFile::size).sum();
    }

    /** The type's stored files as the case was read; the answer when nothing is new. */
    public int documentosEnviados() {
        return documentosEnviados;
    }

    /** The upload's records were written: the reservation is now the case's real count. */
    public void committed() {
        committed = true;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (!committed && !files.isEmpty()) {
                reservations.release(capability.caseId(), tipo, bytes(), files.size());
            }
        } finally {
            parts.forEach(UploadPart::delete);
            permit.close();
        }
    }
}
