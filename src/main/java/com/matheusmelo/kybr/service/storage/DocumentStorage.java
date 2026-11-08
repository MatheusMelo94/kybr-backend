package com.matheusmelo.kybr.service.storage;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Where seller documents are kept: private, encrypted at rest, under generated keys
 * ({@code workspace/case/document}), in two separate areas. Received files wait in
 * {@link Area#QUARANTINE} until the scan job accepts them into {@link Area#FINAL}. A failure throws
 * {@link DocumentStorageException}.
 */
public interface DocumentStorage {

    /** The two areas, each its own bucket and key in production. */
    enum Area {
        /** Unscanned files: written by an upload, read only by the scan job, never served. */
        QUARANTINE,
        /** Accepted files: never read back by the API in this milestone. */
        FINAL
    }

    /** The object key of a document, the same in both areas. */
    static String key(UUID workspaceId, UUID caseId, UUID documentId) {
        return workspaceId + "/" + caseId + "/" + documentId;
    }

    /**
     * The final key one scan attempt writes: the document's key plus the attempt's lease owner, so two
     * workers racing on one document never share an object, and a loser can delete its own safely.
     */
    static String attemptKey(UUID workspaceId, UUID caseId, UUID documentId, String leaseOwner) {
        return key(workspaceId, caseId, documentId) + "/" + leaseOwner;
    }

    /** The prefix of every object of a case. */
    static String casePrefix(UUID workspaceId, UUID caseId) {
        return workspaceId + "/" + caseId + "/";
    }

    /** The prefix of every object of a workspace. */
    static String prefix(UUID workspaceId) {
        return workspaceId + "/";
    }

    /**
     * @param content read to the end; {@code size} bytes
     * @param metadata stored with the object (hashes, sizes); never personal data
     */
    void put(Area area, String key, InputStream content, long size, String contentType, Map<String, String> metadata);

    /**
     * Opens a quarantined object for the scan job; the caller closes the stream.
     *
     * @throws IllegalArgumentException for {@link Area#FINAL}, which is never read back here
     * @throws DocumentStorageException when it can't be read, a missing object included
     */
    InputStream get(Area area, String key);

    /** Deletes the object; a missing object is not an error. */
    void delete(Area area, String key);

    boolean exists(Area area, String key);

    /** Every object under {@code prefix}; an implementation pages through its listing lazily. */
    Stream<StoredObject> list(Area area, String prefix);

    /** Refuses a read of the final area. */
    static void requireQuarantine(Area area) {
        if (area != Area.QUARANTINE) {
            throw new IllegalArgumentException("only quarantined objects are read back");
        }
    }
}
