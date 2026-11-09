package com.matheusmelo.kybr.service.upload;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.document.DocumentRequest;
import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.DocumentRequestRepository;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.service.link.CaseReadService;
import com.matheusmelo.kybr.service.link.CaseTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Resolves an upload token to an {@link UploadCapability}, or 404 {@code CASO_NAO_ENCONTRADO}.
 *
 * <p>The token is tried first as the case token (one indexed query, revoked tokens excluded), then
 * as a document-request token (one indexed query). An off-pattern token makes no query. Unknown,
 * foreign, revoked, closed and expired tokens all answer the same 404.
 *
 * <p>A case token allows the types of the case's pending document items, and only while the case
 * is {@code pending}. A document-request token allows its own types while it is open and its case's
 * token hasn't been revoked.
 */
@Service
public class UploadCapabilityResolver {

    private static final Set<CodigoResultado> DOCUMENT_ITEMS =
            EnumSet.of(CodigoResultado.DOCUMENTO_RESPONSAVEL, CodigoResultado.DOCUMENTO_SOLICITADO);

    private final CaseReadService caseReads;
    private final LinkCaseRepository cases;
    private final DocumentRequestRepository requests;
    private final Clock clock;

    public UploadCapabilityResolver(CaseReadService caseReads, LinkCaseRepository cases,
            DocumentRequestRepository requests, Clock clock) {
        this.caseReads = caseReads;
        this.cases = cases;
        this.requests = requests;
        this.clock = clock;
    }

    public UploadCapability resolve(Workspace workspace, String token) {
        if (!CaseTokens.isWellFormed(token)) {
            throw notFound();
        }
        Optional<LinkCase> byCaseToken = caseReads.lookup(workspace, token);
        if (byCaseToken.isPresent()) {
            LinkCase linkCase = byCaseToken.get();
            return new UploadCapability(linkCase, linkTokenTipos(linkCase), UploadCapability.Origem.LINK_TOKEN, null);
        }
        Instant now = clock.instant();
        DocumentRequest request = requests.findByTokenHash(CaseTokens.hash(token))
                .filter(r -> workspace.id().equals(r.workspaceId()))
                .filter(r -> r.revogadoEm() == null && r.encerradoEm() == null)
                .filter(r -> r.expiraEm() != null && r.expiraEm().isAfter(now))
                .orElseThrow(UploadCapabilityResolver::notFound);
        LinkCase linkCase = cases.findById(request.caseId())
                .filter(c -> workspace.id().equals(c.workspaceId()) && c.tokenRevogadoEm() == null)
                .orElseThrow(UploadCapabilityResolver::notFound);
        return new UploadCapability(linkCase, Set.copyOf(request.tipos()), UploadCapability.Origem.DOCUMENT_REQUEST,
                request.id());
    }

    private static Set<DocumentoTipo> linkTokenTipos(LinkCase linkCase) {
        if (linkCase.estado() != EstadoCaso.pending || linkCase.resultado() == null
                || linkCase.resultado().itens() == null) {
            return Set.of();
        }
        return linkCase.resultado().itens().stream()
                .filter(item -> DOCUMENT_ITEMS.contains(item.codigo()))
                .map(ItemResultado::documentoTipo)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.CASO_NAO_ENCONTRADO);
    }
}
