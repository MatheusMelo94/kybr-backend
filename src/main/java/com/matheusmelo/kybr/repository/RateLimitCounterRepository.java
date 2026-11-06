package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.RateLimitCounter;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RateLimitCounterRepository
        extends MongoRepository<RateLimitCounter, String>, RateLimitCounterRepositoryCustom {
}
