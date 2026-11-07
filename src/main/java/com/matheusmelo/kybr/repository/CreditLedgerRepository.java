package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.link.CreditLedger;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CreditLedgerRepository extends MongoRepository<CreditLedger, String> {
}
