package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.workspace.RuntimeFlags;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RuntimeFlagsRepository extends MongoRepository<RuntimeFlags, String> {
}
