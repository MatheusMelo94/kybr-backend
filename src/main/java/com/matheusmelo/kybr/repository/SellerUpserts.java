package com.matheusmelo.kybr.repository;

import com.matheusmelo.kybr.model.link.OrigemSeller;
import com.matheusmelo.kybr.model.link.Seller;
import com.matheusmelo.kybr.model.link.StatusSeller;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The atomic seller writes the link needs: upserts on the unique {@code {workspace_id, cnpj}}. A
 * custom component because a find-then-save would overwrite fields a concurrent writer set; each
 * upsert touches only its own fields, and sets identity fields on insert only.
 */
@Component
public class SellerUpserts {

    private final MongoTemplate mongo;

    public SellerUpserts(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** The seller of (workspace, CNPJ) is being verified through the link; created when new. */
    public void markLinkVerificationStarted(UUID workspaceId, String cnpj) {
        Query query = Query.query(Criteria.where(Seller.WORKSPACE_ID).is(workspaceId).and(Seller.CNPJ).is(cnpj));
        Update update = new Update()
                .set("status", StatusSeller.em_verificacao)
                .setOnInsert("_id", UUID.randomUUID())
                .setOnInsert("origem", OrigemSeller.link);
        mongo.upsert(query, update, Seller.class);
    }

    /**
     * Records a verification's verdict on the seller of (workspace, CNPJ): only the verdict fields
     * are set, so other fields survive; created when new.
     */
    public void recordVerdict(UUID workspaceId, String cnpj, String razaoSocial, StatusSeller status,
            Instant verificadoEm, UUID verificacaoId) {
        Query query = Query.query(Criteria.where(Seller.WORKSPACE_ID).is(workspaceId).and(Seller.CNPJ).is(cnpj));
        Update update = new Update()
                .set("status", status)
                .set("razao_social", razaoSocial)
                .set("verificado_em", verificadoEm)
                .set("ultima_verificacao_id", verificacaoId)
                .setOnInsert("_id", UUID.randomUUID())
                .setOnInsert("origem", OrigemSeller.link);
        mongo.upsert(query, update, Seller.class);
    }
}
