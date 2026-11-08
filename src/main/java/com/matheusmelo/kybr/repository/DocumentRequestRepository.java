package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.document.DocumentRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DocumentRequestRepository extends MongoRepository<DocumentRequest, UUID> {

    /** One indexed query, like the case token lookup. */
    Optional<DocumentRequest> findByTokenHash(String tokenHash);

    List<DocumentRequest> findByCaseId(UUID caseId);
}
