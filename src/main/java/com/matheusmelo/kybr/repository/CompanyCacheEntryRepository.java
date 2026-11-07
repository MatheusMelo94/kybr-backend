package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.link.CompanyCacheEntry;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CompanyCacheEntryRepository extends MongoRepository<CompanyCacheEntry, String> {
}
