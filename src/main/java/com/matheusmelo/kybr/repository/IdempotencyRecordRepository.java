package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.IdempotencyRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface IdempotencyRecordRepository
        extends MongoRepository<IdempotencyRecord, String>, IdempotencyRecordRepositoryCustom {
}
