package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.workspace.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkspaceRepository extends MongoRepository<Workspace, UUID> {

    Optional<Workspace> findBySlug(String slug);
}
