package com.matheusmelo.kybr.service.link;

import com.matheusmelo.kybr.dto.validation.ResponsavelValidator;
import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.Processamento;
import com.matheusmelo.kybr.model.link.ResumoAuditoria;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.SellerUpserts;
import com.matheusmelo.kybr.service.idempotency.IdempotencyAttempt;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService;
import com.matheusmelo.kybr.service.verification.VerificationDispatcher;
import com.matheusmelo.kybr.util.Cnpj;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Starts a seller's verification. The link's rate limits are applied first, so a denial writes
 * nothing. Then, in one transaction that also completes the idempotency attempt (status 201): the
 * case is created ({@code processing}, {@code PENDENTE}, the token's hash, a copy of the
 * workspace's policy, {@code ref} and {@code ext_id} untouched), the seller is upserted, and the
 * audit event is written. The case is handed to the dispatcher only after the commit, so a
 * rollback never dispatches.
 */
@Service
public class VerificationStartService {

    static final int CREATED = 201;

    private final LinkRateLimits limits;
    private final IdempotencyService idempotency;
    private final LinkCaseRepository cases;
    private final SellerUpserts sellers;
    private final AuditEventRepository audits;
    private final CaseTokens tokens;
    private final VerificationDispatcher dispatcher;
    private final Clock clock;

    public VerificationStartService(LinkRateLimits limits, IdempotencyService idempotency, LinkCaseRepository cases,
            SellerUpserts sellers, AuditEventRepository audits, CaseTokens tokens,
            VerificationDispatcher dispatcher, Clock clock) {
        this.limits = limits;
        this.idempotency = idempotency;
        this.cases = cases;
        this.sellers = sellers;
        this.audits = audits;
        this.tokens = tokens;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    /**
     * Starts the verification and returns {@code response} applied to the new case token; that
     * value is also what the idempotency record replays.
     */
    public <T> T start(Workspace workspace, ClientIp clientIp, StartCommand command, IdempotencyAttempt attempt,
            Function<String, T> response) {
        String cnpj = command.cnpj();
        if (!Cnpj.isValidCanonical(cnpj)) {
            throw new ApiException(ErrorCode.CNPJ_INVALIDO, "cnpj");
        }
        limits.verify(clientIp, workspace, cnpj);
        AtomicReference<UUID> committed = new AtomicReference<>();
        T result = idempotency.completeInTransaction(attempt, CREATED, () -> {
            String token = tokens.newToken();
            LinkCase created = cases.insert(newCase(workspace, command, cnpj, token));
            sellers.markLinkVerificationStarted(workspace.id(), cnpj);
            audits.insert(auditEvent(workspace, clientIp, created));
            committed.set(created.id());
            return response.apply(token);
        });
        // completeInTransaction has returned, so the transaction has committed
        dispatcher.submit(committed.get());
        return result;
    }

    private LinkCase newCase(Workspace workspace, StartCommand command, String cnpj, String token) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return new LinkCase(UUID.randomUUID(), workspace.id(), CaseTokens.hash(token), EstadoCaso.processing, now,
                cnpj, ResponsavelValidator.normalize(command.responsavel()), command.ref(), command.extId(),
                workspace.politica(), null, null, null, null, Processamento.pendente(now), null, null,
                command.idempotencyKey().toLowerCase(Locale.ROOT), null);
    }

    private AuditEvent auditEvent(Workspace workspace, ClientIp clientIp, LinkCase created) {
        boolean known = clientIp.isPerClientIdentity();
        return new AuditEvent(UUID.randomUUID(), workspace.id(), TipoAuditoria.VERIFICACAO_INICIADA, Ator.vendedor,
                created.id(), created.criadoEm(), known ? clientIp.ip() : null, !known, null, null,
                ResumoAuditoria.ofCnpj(maskCnpj(created.cnpj())));
    }

    /** The first two and last two characters only: {@code 11.***.***\/****-81}. */
    static String maskCnpj(String canonical) {
        return canonical.substring(0, 2) + ".***.***/****-" + canonical.substring(12);
    }
}
