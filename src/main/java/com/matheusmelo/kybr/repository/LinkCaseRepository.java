package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.link.LinkCase;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LinkCaseRepository extends MongoRepository<LinkCase, UUID> {

    /** One indexed query: an unknown token and another workspace's token miss alike. */
    Optional<LinkCase> findByTokenHashAndWorkspaceId(String tokenHash, UUID workspaceId);
}
