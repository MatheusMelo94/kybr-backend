package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.link.Contato;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.TipoOutbox;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The writes of a contact change: a targeted {@code $set} of a case's contact (so a concurrent
 * verification's fields are never overwritten), and the case's pending outbox notification (found,
 * inserted or withdrawn), which the unique partial index keeps to one per case.
 */
@Component
public class ContactChangeWrites {

    private final MongoTemplate mongo;

    public ContactChangeWrites(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void setContato(UUID caseId, Contato contato) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(caseId)), new Update().set("contato", contato),
                LinkCase.class);
    }

    /** The case's unprocessed contact-change notification, if any. */
    public Optional<OutboxEvent> findPending(UUID caseId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where(OutboxEvent.CASE_ID).is(caseId)
                .and(OutboxEvent.TIPO).is(TipoOutbox.CONTATO_ALTERADO).and(OutboxEvent.PENDENTE).is(true)),
                OutboxEvent.class));
    }

    public void insert(OutboxEvent event) {
        mongo.insert(event);
    }

    /** Withdraws an unprocessed notification (the address it was about is current again). */
    public void deletePending(UUID eventId) {
        mongo.remove(Query.query(Criteria.where("_id").is(eventId).and(OutboxEvent.PENDENTE).is(true)),
                OutboxEvent.class);
    }
}
