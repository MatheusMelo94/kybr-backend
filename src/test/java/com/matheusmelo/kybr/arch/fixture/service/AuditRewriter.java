package com.matheusmelo.kybr.arch.fixture.service;

import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Rule fixture, never run: every way of changing an audit event after it was written. */
public class AuditRewriter {

    AuditEventRepository audits;
    MongoTemplate mongo;

    void overwrite(AuditEvent event) {
        audits.save(event);
    }

    void erase(AuditEvent event) {
        audits.delete(event);
    }

    void patch() {
        mongo.updateFirst(new Query(), new Update().set("x", 1), AuditEvent.class);
    }

    void purge() {
        mongo.remove(new Query(), AuditEvent.COLLECTION);
    }
}
