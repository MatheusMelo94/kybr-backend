package com.matheusmelo.kybr.repository;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.document.MotivoRecusa;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The scan job's claims on received files, each {@link Document} row being its own job. A custom
 * component is justified by the layer rules: a claim must be one atomic, sorted
 * {@code findAndModify} (supported by every server version in use), and every later write is
 * fenced on the claimant's {@code lease_owner}, so a worker that lost its lease writes nothing.
 *
 * <p>Claimable: {@code RECEBIDO} and due ({@code proxima_tentativa ≤ now}), or {@code VERIFICANDO}
 * with an expired lease (its worker died), and with an attempt left. A claim sets
 * {@code VERIFICANDO}, the owner and {@code lease_until = now + lease}, and counts one attempt. A
 * due file at the attempt ceiling is claimed only to be refused ({@link #claimExhausted}).
 */
@Component
public class ScanClaims {

    private static final FindAndModifyOptions RETURN_NEW = FindAndModifyOptions.options().returnNew(true);

    /** The backlog as the gauges show it. */
    public record Backlog(long recebidos, long verificando, Instant oldestPending, long stuck) {
    }

    private final MongoTemplate mongo;

    public ScanClaims(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Claims the file due earliest that still has an attempt left ({@code tentativas < maxAttempts});
     * empty when nothing is due.
     */
    public Optional<Document> claimNext(int maxAttempts, Instant now, String owner, Duration lease) {
        Criteria claimable = new Criteria().andOperator(due(now), where(Document.TENTATIVAS).lt(maxAttempts));
        Query query = Query.query(claimable).with(Sort.by(Document.PROXIMA_TENTATIVA));
        Update update = new Update()
                .set(Document.ESTADO, EstadoDocumento.VERIFICANDO)
                .set(Document.LEASE_OWNER, owner)
                .set(Document.LEASE_UNTIL, now.plus(lease))
                .inc(Document.TENTATIVAS, 1);
        return Optional.ofNullable(mongo.findAndModify(query, update, RETURN_NEW, Document.class));
    }

    /**
     * Claims a due file that has used every attempt (a run died before deciding it), without counting
     * another; the caller refuses it. Empty when there is none.
     */
    public Optional<Document> claimExhausted(int maxAttempts, Instant now, String owner, Duration lease) {
        Criteria exhausted = new Criteria().andOperator(due(now), where(Document.TENTATIVAS).gte(maxAttempts));
        Update update = new Update()
                .set(Document.ESTADO, EstadoDocumento.VERIFICANDO)
                .set(Document.LEASE_OWNER, owner)
                .set(Document.LEASE_UNTIL, now.plus(lease));
        return Optional.ofNullable(mongo.findAndModify(Query.query(exhausted).with(Sort.by(Document.PROXIMA_TENTATIVA)),
                update, RETURN_NEW, Document.class));
    }

    /** Due: received and its time has come, or claimed by a worker whose lease expired. */
    private static Criteria due(Instant now) {
        return new Criteria().orOperator(
                where(Document.ESTADO).is(EstadoDocumento.RECEBIDO).and(Document.PROXIMA_TENTATIVA).lte(now),
                where(Document.ESTADO).is(EstadoDocumento.VERIFICANDO).and(Document.LEASE_UNTIL).lt(now));
    }

    /** Due again at {@code next}, back to {@code RECEBIDO}; false when {@code owner} lost the lease. */
    public boolean reschedule(UUID id, String owner, Instant next) {
        return fenced(id, owner, release().set(Document.ESTADO, EstadoDocumento.RECEBIDO)
                .set(Document.PROXIMA_TENTATIVA, next));
    }

    /**
     * Due again at {@code next} without the claim counting as an attempt (an infrastructure fault such
     * as stale scanner signatures, which the alert covers); false when {@code owner} lost the lease.
     */
    public boolean rescheduleUncounted(UUID id, String owner, Instant next) {
        return fenced(id, owner, release().set(Document.ESTADO, EstadoDocumento.RECEBIDO)
                .set(Document.PROXIMA_TENTATIVA, next).inc(Document.TENTATIVAS, -1));
    }

    /**
     * Marks the file refused: it no longer counts toward the duplicate guard, and its quarantine key
     * is cleared (the caller deletes the object). False when {@code owner} lost the lease. Meant to
     * run inside the transaction that also releases the file's share on the case.
     */
    public boolean refuse(UUID id, String owner, MotivoRecusa motivo) {
        return fenced(id, owner, release().set(Document.ESTADO, EstadoDocumento.RECUSADO)
                .set(Document.MOTIVO_RECUSA, motivo).unset(Document.ATIVO).unset(Document.QUARENTENA_KEY)
                .unset(Document.PROXIMA_TENTATIVA));
    }

    /**
     * Marks the file accepted with its final object; false when {@code owner} lost the lease. Meant
     * to run inside the transaction that also updates the case.
     */
    public boolean accept(UUID id, String owner, String storageKey, String sha256, long bytesArmazenados) {
        return fenced(id, owner, release().set(Document.ESTADO, EstadoDocumento.ACEITO)
                .set(Document.STORAGE_KEY, storageKey).set(Document.SHA256, sha256)
                .set(Document.BYTES_ARMAZENADOS, bytesArmazenados).unset(Document.PROXIMA_TENTATIVA));
    }

    /** Clears the quarantine key once its object is deleted (after acceptance). */
    public void quarantineDeleted(UUID id) {
        mongo.updateFirst(Query.query(where("_id").is(id).and(Document.ESTADO).is(EstadoDocumento.ACEITO)),
                new Update().unset(Document.QUARENTENA_KEY), Document.class);
    }

    /** Counts what is waiting, and what has waited longer than {@code stuckAfter}. */
    public Backlog backlog(Instant now, Duration stuckAfter) {
        long recebidos = mongo.count(Query.query(where(Document.ESTADO).is(EstadoDocumento.RECEBIDO)), Document.class);
        long verificando = mongo.count(Query.query(where(Document.ESTADO).is(EstadoDocumento.VERIFICANDO)),
                Document.class);
        Criteria pending = where(Document.ESTADO).in(List.of(EstadoDocumento.RECEBIDO, EstadoDocumento.VERIFICANDO));
        Document oldest = mongo.findOne(Query.query(pending).with(Sort.by(Document.CRIADO_EM)).limit(1), Document.class);
        long stuck = mongo.count(Query.query(where(Document.ESTADO)
                .in(List.of(EstadoDocumento.RECEBIDO, EstadoDocumento.VERIFICANDO))
                .and(Document.CRIADO_EM).lt(now.minus(stuckAfter))), Document.class);
        return new Backlog(recebidos, verificando, oldest == null ? null : oldest.criadoEm(), stuck);
    }

    private boolean fenced(UUID id, String owner, Update update) {
        Query query = Query.query(where("_id").is(id).and(Document.ESTADO).is(EstadoDocumento.VERIFICANDO)
                .and(Document.LEASE_OWNER).is(owner));
        return mongo.updateFirst(query, update, Document.class).getMatchedCount() == 1;
    }

    private static Update release() {
        return new Update().unset(Document.LEASE_OWNER).unset(Document.LEASE_UNTIL);
    }
}
