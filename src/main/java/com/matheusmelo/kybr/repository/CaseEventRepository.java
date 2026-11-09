package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.link.CaseEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CaseEventRepository extends MongoRepository<CaseEvent, UUID> {

    List<CaseEvent> findByCaseIdOrderByOcorridoEmAsc(UUID caseId);
}
