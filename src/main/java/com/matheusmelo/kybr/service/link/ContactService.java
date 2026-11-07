package com.matheusmelo.kybr.service.link;

import com.matheusmelo.kybr.dto.validation.ContactEmailValidator;
import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.RateLimitExceededException;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.Contato;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.ResumoAuditoria;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.link.TipoOutbox;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.ContactChangeWrites;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.service.TransactionRetry;
import com.matheusmelo.kybr.service.crypto.FieldCipher;
import com.matheusmelo.kybr.service.crypto.FieldDecryptException;
import com.matheusmelo.kybr.util.EmailMasker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The seller's contact e-mail for a case. Checks run in this order: the per-IP limit, the case
 * lookup (404 {@code CASO_NAO_ENCONTRADO}), the address (422 {@code EMAIL_INVALIDO}), a decided
 * case (409 {@code CASO_DECIDIDO}), and, only for an actual change, the per-case daily limit (429
 * {@code LIMITE_TENTATIVAS}). The same address again, in any letter case, changes nothing.
 *
 * <p>A change is one transaction: the case gets the address encrypted (AAD = case id) and its
 * masked form; an audit event records both masked values and the client IP; and the old address,
 * encrypted (AAD = event id), is queued once per case for a later notification. Further changes
 * keep the first queued address; changing back to it withdraws the notification. The raw address
 * is never logged.
 */
@Service
public class ContactService {

    static final String METRIC = "kybr.link.contact";
    static final Duration OUTBOX_EXPIRY = Duration.ofDays(30);

    private final LinkRateLimits limits;
    private final CaseReadService caseReads;
    private final LinkCaseRepository cases;
    private final ContactChangeWrites writes;
    private final AuditEventRepository audits;
    private final FieldCipher cipher;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MeterRegistry meters;

    public ContactService(LinkRateLimits limits, CaseReadService caseReads, LinkCaseRepository cases,
            ContactChangeWrites writes, AuditEventRepository audits, FieldCipher cipher,
            PlatformTransactionManager transactionManager, Clock clock, MeterRegistry meters) {
        this.limits = limits;
        this.caseReads = caseReads;
        this.cases = cases;
        this.writes = writes;
        this.audits = audits;
        this.cipher = cipher;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.meters = meters;
    }

    public void change(Workspace workspace, ClientIp clientIp, String token, String email) {
        limits.contact(clientIp, workspace);
        LinkCase linkCase = caseReads.find(workspace, token);
        if (!ContactEmailValidator.isValid(email)) {
            throw new ApiException(ErrorCode.EMAIL_INVALIDO, "email");
        }
        refuseIfDecided(linkCase);
        String address = ContactEmailValidator.normalize(email);
        if (currentAddress(linkCase).filter(current -> sameAddress(current, address)).isPresent()) {
            count("unchanged");
            return;
        }
        try {
            limits.contactChange(linkCase.id());
        } catch (RateLimitExceededException e) {
            count("limited");
            throw e;
        }
        Boolean changed = TransactionRetry.DEFAULT.execute(() -> transactions.execute(
                status -> apply(workspace, clientIp, linkCase.id(), address)));
        count(Boolean.TRUE.equals(changed) ? "changed" : "unchanged");
    }

    /**
     * Re-reads the case inside the transaction, so concurrent changes chain correctly and a case
     * decided since the first check is still refused.
     */
    private boolean apply(Workspace workspace, ClientIp clientIp, UUID caseId, String address) {
        LinkCase current = cases.findById(caseId).orElseThrow(() -> new ApiException(ErrorCode.CASO_NAO_ENCONTRADO));
        refuseIfDecided(current);
        Optional<String> previous = currentAddress(current);
        if (previous.filter(old -> sameAddress(old, address)).isPresent()) {
            return false;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String masked = EmailMasker.mask(address);
        writes.setContato(caseId, new Contato(cipher.encrypt(address, caseId), masked, now));
        String previousMasked = current.contato() == null ? null : current.contato().emailMascarado();
        boolean known = clientIp.isPerClientIdentity();
        audits.insert(new AuditEvent(UUID.randomUUID(), workspace.id(), TipoAuditoria.CONTATO_ALTERADO,
                Ator.vendedor, caseId, now, known ? clientIp.ip() : null, !known, null, null,
                ResumoAuditoria.ofContato(previousMasked, masked)));
        previous.ifPresent(old -> queueNotification(caseId, old, address, now));
        return true;
    }

    /** At most one pending notification per case, holding the first replaced address. */
    private void queueNotification(UUID caseId, String old, String address, Instant now) {
        Optional<OutboxEvent> pending = writes.findPending(caseId);
        if (pending.isPresent()) {
            OutboxEvent event = pending.get();
            if (decrypt(event.payloadCifrado(), event.id()).filter(first -> sameAddress(first, address)).isPresent()) {
                writes.deletePending(event.id());
            }
            return;
        }
        UUID eventId = UUID.randomUUID();
        writes.insert(new OutboxEvent(eventId, TipoOutbox.CONTATO_ALTERADO, caseId, cipher.encrypt(old, eventId), now,
                now.plus(OUTBOX_EXPIRY)));
    }

    private void refuseIfDecided(LinkCase linkCase) {
        if (linkCase.estado() == EstadoCaso.approved || linkCase.estado() == EstadoCaso.blocked) {
            count("decided");
            throw new ApiException(ErrorCode.CASO_DECIDIDO);
        }
    }

    /**
     * Addresses are compared ignoring case, so a case-only edit is no change: no audit entry, no
     * notice and no use of the daily change limit.
     */
    private static boolean sameAddress(String a, String b) {
        return a.equalsIgnoreCase(b);
    }

    /** The case's current address, or empty when there is none or it can't be decrypted. */
    private Optional<String> currentAddress(LinkCase linkCase) {
        return linkCase.contato() == null || linkCase.contato().emailCifrado() == null ? Optional.empty()
                : decrypt(linkCase.contato().emailCifrado(), linkCase.id());
    }

    private Optional<String> decrypt(byte[] ciphertext, UUID documentId) {
        try {
            return Optional.of(cipher.decrypt(ciphertext, documentId));
        } catch (FieldDecryptException e) {
            return Optional.empty();
        }
    }

    private void count(String resultado) {
        meters.counter(METRIC, "resultado", resultado).increment();
    }
}
