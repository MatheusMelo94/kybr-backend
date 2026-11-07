package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.link.AuditEvent;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AuditEventRepository extends MongoRepository<AuditEvent, UUID> {
}
