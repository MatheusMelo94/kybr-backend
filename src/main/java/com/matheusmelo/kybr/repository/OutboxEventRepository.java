package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.link.OutboxEvent;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OutboxEventRepository extends MongoRepository<OutboxEvent, UUID> {
}
