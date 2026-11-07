package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.link.Verification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface VerificationRepository extends MongoRepository<Verification, UUID> {

    List<Verification> findByWorkspaceIdAndCaseId(UUID workspaceId, UUID caseId);
}
