package com.matheusmelo.kybr.repository;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.ResultadoEnvio;
import com.matheusmelo.kybr.model.link.TipoOutbox;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The e-mail dispatcher's claims on outbox events, each event being its own job. A custom
 * component is justified by the layer rules: a claim must be one atomic, sorted
 * {@code findAndModify} (supported by every server version in use), and every later write is
 * fenced on the claimant's {@code lease_owner}, so a dispatcher run that lost its lease writes
 * nothing.
 *
 * <p>Claimable: pending, of an enabled type, not expired, due ({@code proxima_tentativa ≤ now},
 * or absent on an event stored before delivery existed), and unleased or with an expired lease. A
 * claim sets the owner and {@code lease_until = now + lease} and counts one attempt. Leaving the
 * queue (processed or failed) sets {@code pendente=false} too, which keeps the coalescing index
 * (one pending event per case and type) correct.
 */
@Component
public class OutboxClaims {

    private static final FindAndModifyOptions RETURN_NEW = FindAndModifyOptions.options().returnNew(true);

    private final MongoTemplate mongoTemplate;

    public OutboxClaims(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Claims the claimable event of one of {@code tipos} due earliest; empty when there is none. */
    public Optional<OutboxEvent> claimNext(Set<TipoOutbox> tipos, Instant now, String owner, Duration lease) {
        if (tipos.isEmpty()) {
            return Optional.empty();
        }
        Criteria claimable = new Criteria().andOperator(
                where(OutboxEvent.PENDENTE).is(true).and(OutboxEvent.TIPO).in(tipos).and(OutboxEvent.EXPIRA_EM).gt(now),
                new Criteria().orOperator(where(OutboxEvent.PROXIMA_TENTATIVA).lte(now),
                        where(OutboxEvent.PROXIMA_TENTATIVA).is(null)),
                new Criteria().orOperator(where(OutboxEvent.LEASE_OWNER).is(null),
                        where(OutboxEvent.LEASE_UNTIL).lt(now)));
        Query query = Query.query(claimable).with(Sort.by(OutboxEvent.PROXIMA_TENTATIVA));
        Update update = new Update()
                .set(OutboxEvent.LEASE_OWNER, owner)
                .set(OutboxEvent.LEASE_UNTIL, now.plus(lease))
                .inc(OutboxEvent.TENTATIVAS, 1);
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update, RETURN_NEW, OutboxEvent.class));
    }

    /** The e-mail was handled (sent, or dropped by a cap); false when {@code owner} lost the lease. */
    public boolean markProcessed(UUID id, String owner, Instant now, ResultadoEnvio resultado) {
        return fenced(id, owner, release().set(OutboxEvent.PROCESSADO_EM, now).set(OutboxEvent.PENDENTE, false)
                .set(OutboxEvent.RESULTADO_ENVIO, resultado));
    }

    /** Due again at {@code next}; false when {@code owner} lost the lease. */
    public boolean reschedule(UUID id, String owner, Instant next) {
        return fenced(id, owner, release().set(OutboxEvent.PROXIMA_TENTATIVA, next));
    }

    /** Delivery gave up; false when {@code owner} lost the lease. */
    public boolean markFailed(UUID id, String owner, Instant now) {
        return fenced(id, owner, release().set(OutboxEvent.FALHOU_EM, now).set(OutboxEvent.PENDENTE, false));
    }

    private static Update release() {
        return new Update().set(OutboxEvent.LEASE_OWNER, null).set(OutboxEvent.LEASE_UNTIL, null);
    }

    private boolean fenced(UUID id, String owner, Update update) {
        Query query = Query.query(where("_id").is(id).and(OutboxEvent.PENDENTE).is(true)
                .and(OutboxEvent.LEASE_OWNER).is(owner));
        return mongoTemplate.updateFirst(query, update, OutboxEvent.class).getMatchedCount() == 1;
    }
}
