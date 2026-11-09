package com.matheusmelo.kybr.service.link;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Finds a case by its token within a workspace. An off-pattern token (no query), an unknown token
 * and another workspace's token are the same 404 {@code CASO_NAO_ENCONTRADO}; the last two miss in
 * the same single indexed query on {@code {token_hash, workspace_id}}, so they also take comparable
 * time. A token revoked by retention is not found either, decided after that same query, so it is
 * indistinguishable from an unknown one. Reads go to the primary.
 */
@Service
public class CaseReadService {

    private final LinkCaseRepository repository;
    private final LinkRateLimits limits;

    public CaseReadService(LinkCaseRepository repository, LinkRateLimits limits) {
        this.repository = repository;
        this.limits = limits;
    }

    /**
     * The seller's case read: the per-IP case-read limit first (429 {@code LIMITE_TENTATIVAS}), so
     * a throttled request makes no case query; then {@link #find}.
     */
    public LinkCase read(Workspace workspace, ClientIp clientIp, String token) {
        limits.caseRead(clientIp, workspace);
        return find(workspace, token);
    }

    public LinkCase find(Workspace workspace, String token) {
        return lookup(workspace, token).orElseThrow(CaseReadService::notFound);
    }

    /** The case a live token addresses, or empty: off-pattern (no query), unknown, foreign or revoked. */
    public Optional<LinkCase> lookup(Workspace workspace, String token) {
        if (!CaseTokens.isWellFormed(token)) {
            return Optional.empty();
        }
        return repository.findByTokenHashAndWorkspaceId(CaseTokens.hash(token), workspace.id())
                .filter(linkCase -> linkCase.tokenRevogadoEm() == null);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.CASO_NAO_ENCONTRADO);
    }
}
