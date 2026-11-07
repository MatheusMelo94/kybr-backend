package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.link.Seller;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SellerRepository extends MongoRepository<Seller, UUID> {

    Optional<Seller> findByWorkspaceIdAndCnpj(UUID workspaceId, String cnpj);
}
