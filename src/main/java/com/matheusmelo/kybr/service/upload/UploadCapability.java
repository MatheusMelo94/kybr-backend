package com.matheusmelo.kybr.service.upload;

import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import java.util.Set;
import java.util.UUID;

/**
 * What a token lets its holder upload: documents of the allowed types to one case.
 *
 * @param linkCase the case, as read when the token was resolved
 * @param tiposPermitidos the types it may send; empty when the case asks for nothing (any upload is
 *     then refused as a format error)
 * @param origem the kind of token
 * @param documentRequestId the document request, when {@code origem} is {@link Origem#DOCUMENT_REQUEST}
 */
public record UploadCapability(LinkCase linkCase, Set<DocumentoTipo> tiposPermitidos, Origem origem,
        UUID documentRequestId) {

    /** The kind of token that granted the capability. */
    public enum Origem {
        /** The case token from the seller link. */
        LINK_TOKEN,
        /** A scoped, expiring document-request token. */
        DOCUMENT_REQUEST
    }

    public UploadCapability {
        tiposPermitidos = Set.copyOf(tiposPermitidos);
    }

    public UUID caseId() {
        return linkCase.id();
    }

    public UUID workspaceId() {
        return linkCase.workspaceId();
    }
}
