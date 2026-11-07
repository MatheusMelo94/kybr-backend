package com.matheusmelo.kybr.repository;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.EstadoProcessamento;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.MotivoRevisao;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.link.Revisao;
import com.matheusmelo.kybr.model.link.Variante;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Background verification claims on the case document, which is the job. A custom component is
 * justified by the layer rules: a claim must be one atomic, sorted {@code findAndModify} (never
 * {@code updateOne} with a sort, which older servers lack), and every later write is fenced on the
 * claimant's {@code lease_owner}, so a worker that lost its lease writes nothing.
 *
 * <p>Claimable: {@code PENDENTE} with {@code proxima_tentativa ≤ now}, or {@code EM_ANDAMENTO} with
 * an expired lease. A claim sets {@code EM_ANDAMENTO}, the owner, {@code lease_until = now + lease}
 * and counts one attempt. Fenced writes also run inside a caller's transaction.
 */
@Component
public class ProcessingClaims {

    private static final String TENTATIVAS = LinkCase.PROCESSAMENTO + ".tentativas";
    private static final String MOTIVO_REVISAO = LinkCase.PROCESSAMENTO + ".motivo_revisao";
    private static final FindAndModifyOptions RETURN_NEW = FindAndModifyOptions.options().returnNew(true);

    private final MongoTemplate mongoTemplate;

    public ProcessingClaims(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Claims this case if it's claimable now. */
    public Optional<LinkCase> claim(UUID caseId, Instant now, String owner, Duration lease) {
        return claim(new Criteria().andOperator(where("_id").is(caseId), claimable(now)), now, owner, lease);
    }

    /** Claims the claimable case due earliest. */
    public Optional<LinkCase> claimNext(Instant now, String owner, Duration lease) {
        return claim(claimable(now), now, owner, lease);
    }

    /** Writes the verdict and marks the job done; false when {@code owner} no longer holds the lease. */
    public boolean complete(UUID caseId, String owner, EstadoCaso estado, Resultado resultado, UUID verificacaoId,
            Instant prazoRevisao, Revisao revisao) {
        Update update = release(EstadoProcessamento.CONCLUIDO)
                .set(LinkCase.ESTADO, estado)
                .set("resultado", resultado)
                .set("verificacao_id", verificacaoId)
                .set("prazo_revisao", prazoRevisao)
                .set("revisao", revisao);
        return fenced(caseId, owner, update);
    }

    /**
     * Puts the case back in the queue at {@code next}; false when {@code owner} no longer holds the
     * lease. {@code refundAttempt} takes back the attempt the claim counted (no provider call made).
     */
    public boolean reschedule(UUID caseId, String owner, Instant next, boolean refundAttempt) {
        Update update = release(EstadoProcessamento.PENDENTE).set(LinkCase.PROCESSAMENTO_PROXIMA_TENTATIVA, next);
        if (refundAttempt) {
            update.inc(TENTATIVAS, -1);
        }
        return fenced(caseId, owner, update);
    }

    /**
     * Hands the case to a human reviewer; the seller still sees it as processing. False when
     * {@code owner} no longer holds the lease.
     *
     * @param motivo why; null for a "no data found" variant
     * @param variante and {@code resultado}: set together for a variant, else null
     */
    public boolean toManualReview(UUID caseId, String owner, MotivoRevisao motivo, Variante variante,
            Resultado resultado) {
        Update update = release(EstadoProcessamento.REVISAO_MANUAL).set(MOTIVO_REVISAO, motivo);
        if (variante != null) {
            update.set("variante", variante).set("resultado", resultado);
        }
        return fenced(caseId, owner, update);
    }

    private Optional<LinkCase> claim(Criteria criteria, Instant now, String owner, Duration lease) {
        Query query = Query.query(criteria).with(Sort.by(LinkCase.PROCESSAMENTO_PROXIMA_TENTATIVA));
        Update update = new Update()
                .set(LinkCase.PROCESSAMENTO_ESTADO, EstadoProcessamento.EM_ANDAMENTO)
                .set(LinkCase.PROCESSAMENTO_LEASE_OWNER, owner)
                .set(LinkCase.PROCESSAMENTO_LEASE_UNTIL, now.plus(lease))
                .inc(TENTATIVAS, 1);
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update, RETURN_NEW, LinkCase.class));
    }

    private static Criteria claimable(Instant now) {
        return new Criteria().orOperator(
                where(LinkCase.PROCESSAMENTO_ESTADO).is(EstadoProcessamento.PENDENTE)
                        .and(LinkCase.PROCESSAMENTO_PROXIMA_TENTATIVA).lte(now),
                where(LinkCase.PROCESSAMENTO_ESTADO).is(EstadoProcessamento.EM_ANDAMENTO)
                        .and(LinkCase.PROCESSAMENTO_LEASE_UNTIL).lt(now));
    }

    private static Update release(EstadoProcessamento estado) {
        return new Update()
                .set(LinkCase.PROCESSAMENTO_ESTADO, estado)
                .set(LinkCase.PROCESSAMENTO_LEASE_OWNER, null)
                .set(LinkCase.PROCESSAMENTO_LEASE_UNTIL, null);
    }

    private boolean fenced(UUID caseId, String owner, Update update) {
        Query query = Query.query(where("_id").is(caseId)
                .and(LinkCase.PROCESSAMENTO_ESTADO).is(EstadoProcessamento.EM_ANDAMENTO)
                .and(LinkCase.PROCESSAMENTO_LEASE_OWNER).is(owner));
        return mongoTemplate.updateFirst(query, update, LinkCase.class).getMatchedCount() == 1;
    }
}
