package com.matheusmelo.kybr.service.link;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves a seller link's workspace from its slug. Every failure is the same 404
 * {@code LINK_NAO_ENCONTRADO}, decided from the slug alone: an off-pattern slug (no database
 * read), an unknown slug, or a workspace without a seller contact (not onboarded). A disabled link
 * ({@code link_ativo=false}) is also not found, except where an existing case is read.
 */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    private final WorkspaceRepository repository;

    public LinkService(WorkspaceRepository repository) {
        this.repository = repository;
    }

    /** The workspace of a link open to new activity: its info, lookups, verifications and contact changes. */
    public Workspace resolveActive(String slug) {
        Workspace workspace = resolveReadable(slug);
        if (!workspace.linkAtivo()) {
            throw notFound();
        }
        return workspace;
    }

    /** The workspace for reading an existing case: a disabled link still resolves. */
    public Workspace resolveReadable(String slug) {
        if (slug == null || !Workspace.SLUG_PATTERN.matcher(slug).matches()) {
            throw notFound();
        }
        return repository.findBySlug(slug)
                .filter(workspace -> workspace.contatoVendedor() != null)
                .orElseThrow(LinkService::notFound);
    }

    /**
     * The workspace whose contact may be published on the link page. A stored contact that breaks
     * {@link SellerContactRule} is 404 {@code LINK_NAO_ENCONTRADO}, logged once by reason (never
     * the value). Read on every call; nothing is cached, so a contact change shows at once.
     */
    public Workspace requirePublishableContact(Workspace workspace) {
        SellerContactRule.violation(workspace.contatoVendedor()).ifPresent(reason -> {
            log.warn("event=link_contact_invalid reason={} slug={}", reason, workspace.slug());
            throw notFound();
        });
        return workspace;
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.LINK_NAO_ENCONTRADO);
    }
}
