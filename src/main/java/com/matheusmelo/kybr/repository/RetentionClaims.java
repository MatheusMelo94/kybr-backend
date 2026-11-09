package com.matheusmelo.kybr.repository;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.DocumentRequest;
import com.matheusmelo.kybr.model.link.EstadoRetencao;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OrigemRetencao;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The retention job's claims on cases, each case being its own job. A custom component is
 * justified by the layer rules: the claim must be one atomic, sorted {@code findAndModify} that
 * also revokes the case token, and the conclusion is fenced on the claimant's lease.
 *
 * <p>Claimable: {@code retencao_ate ≤ now} and not concluded, and either never claimed or with an
 * expired lease. The claim sets {@code EM_ANDAMENTO}, the owner and {@code lease_until}, and
 * revokes the token ({@code token_revogado_em}, keeping an earlier revocation) in the same update,
 * so from that moment the token reads as unknown and no upload can commit, before anything is
 * deleted.
 *
 * <p>The backstop for cases never decided: their retention date is recomputed from the seller's
 * last activity while it came from the backstop, and never once a decision set it or a run claimed
 * the case.
 */
@Component
public class RetentionClaims {

    private static final FindAndModifyOptions RETURN_NEW = FindAndModifyOptions.options().returnNew(true);
    private static final String CRIADO_EM = "criado_em";

    private final MongoTemplate mongoTemplate;

    public RetentionClaims(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Claims the claimable case due earliest, revoking its token; empty when there is none. */
    public Optional<LinkCase> claimNext(Instant now, String owner, Duration lease) {
        Criteria claimable = new Criteria().andOperator(
                where(LinkCase.RETENCAO_ATE).lte(now),
                new Criteria().orOperator(
                        where(LinkCase.RETENCAO_ESTADO).is(null),
                        where(LinkCase.RETENCAO_ESTADO).is(EstadoRetencao.PENDENTE),
                        where(LinkCase.RETENCAO_ESTADO).is(EstadoRetencao.EM_ANDAMENTO)
                                .and(LinkCase.RETENCAO_LEASE_UNTIL).lt(now)));
        Query query = Query.query(claimable).with(Sort.by(LinkCase.RETENCAO_ATE));
        Update update = new Update()
                .set(LinkCase.RETENCAO_ESTADO, EstadoRetencao.EM_ANDAMENTO)
                .set(LinkCase.RETENCAO_LEASE_OWNER, owner)
                .set(LinkCase.RETENCAO_LEASE_UNTIL, now.plus(lease))
                .min(LinkCase.TOKEN_REVOGADO_EM, now);
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update, RETURN_NEW, LinkCase.class));
    }

    /**
     * Marks the run done and clears the contact; false when {@code owner} no longer holds the lease.
     * Also runs inside a caller's transaction.
     */
    public boolean conclude(UUID caseId, String owner, Instant now) {
        Query query = Query.query(where("_id").is(caseId)
                .and(LinkCase.RETENCAO_ESTADO).is(EstadoRetencao.EM_ANDAMENTO)
                .and(LinkCase.RETENCAO_LEASE_OWNER).is(owner));
        Update update = new Update()
                .set(LinkCase.RETENCAO_ESTADO, EstadoRetencao.CONCLUIDA)
                .set(LinkCase.RETENCAO_EXECUTADA_EM, now)
                .set(LinkCase.RETENCAO_LEASE_OWNER, null)
                .set(LinkCase.RETENCAO_LEASE_UNTIL, null)
                .unset(LinkCase.CONTATO);
        return mongoTemplate.updateFirst(query, update, LinkCase.class).getMatchedCount() == 1;
    }

    /** Closes the case's document requests that are still open; returns how many. */
    public long closeDocumentRequests(UUID caseId, Instant now) {
        Query query = Query.query(where(DocumentRequest.CASE_ID).is(caseId).and(DocumentRequest.ENCERRADO_EM).is(null));
        return mongoTemplate.updateMulti(query, new Update().set(DocumentRequest.ENCERRADO_EM, now), DocumentRequest.class)
                .getModifiedCount();
    }

    /**
     * The cases whose retention date the backstop owns (not set by a decision, not claimed or
     * purged), with only the fields it needs: id, creation, contact change and the current date.
     * The caller closes the stream.
     */
    public Stream<LinkCase> backstopCandidates() {
        Query query = Query.query(backstopOwned());
        query.fields().include(LinkCase.CRIADO_EM, LinkCase.CONTATO + ".alterado_em", LinkCase.RETENCAO_ATE,
                LinkCase.RETENCAO_ORIGEM);
        return mongoTemplate.stream(query, LinkCase.class);
    }

    /** When the case's latest stored document was received; empty without documents. */
    public Optional<Instant> latestDocumentAt(UUID caseId) {
        Query query = Query.query(where(Document.CASE_ID).is(caseId)).with(Sort.by(Sort.Direction.DESC, CRIADO_EM));
        query.fields().include(CRIADO_EM);
        return Optional.ofNullable(mongoTemplate.findOne(query, org.bson.Document.class, Document.COLLECTION))
                .map(document -> document.getDate(CRIADO_EM)).map(Date::toInstant);
    }

    /**
     * Sets a backstop retention date, unless a decision or a run took the case over meanwhile;
     * false when nothing changed.
     */
    public boolean setBackstop(UUID caseId, Instant retencaoAte) {
        Query query = Query.query(new Criteria().andOperator(where("_id").is(caseId), backstopOwned()));
        Update update = new Update().set(LinkCase.RETENCAO_ATE, retencaoAte)
                .set(LinkCase.RETENCAO_ORIGEM, OrigemRetencao.BACKSTOP);
        return mongoTemplate.updateFirst(query, update, LinkCase.class).getModifiedCount() == 1;
    }

    private static Criteria backstopOwned() {
        return new Criteria().andOperator(
                where(LinkCase.RETENCAO_ORIGEM).in(null, OrigemRetencao.BACKSTOP),
                where(LinkCase.RETENCAO_ESTADO).nin(List.of(EstadoRetencao.EM_ANDAMENTO, EstadoRetencao.CONCLUIDA)));
    }
}
