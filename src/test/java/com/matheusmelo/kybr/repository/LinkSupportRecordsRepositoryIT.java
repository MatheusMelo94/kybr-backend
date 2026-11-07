package com.matheusmelo.kybr.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.config.IndexBootstrap;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.CompanyCacheEntry;
import com.matheusmelo.kybr.model.link.CreditLedger;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.ProjecaoEmpresa;
import com.matheusmelo.kybr.model.link.ResumoAuditoria;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.link.TipoOutbox;
import com.matheusmelo.kybr.model.link.TokenSlot;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Audit events, the outbox, the company cache and the credit ledger on the replica set. */
class LinkSupportRecordsRepositoryIT extends MongoIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private AuditEventRepository audit;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private CompanyCacheEntryRepository cache;

    @Autowired
    private CreditLedgerRepository ledger;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private IndexBootstrap indexBootstrap;

    @BeforeEach
    void ensureIndexes() {
        indexBootstrap.run(null);
    }

    @Test
    void anAuditEventRoundTripsWithItsNullableSessionAndSlot() {
        AuditEvent seller = new AuditEvent(UUID.randomUUID(), UUID.randomUUID(), TipoAuditoria.VERIFICACAO_INICIADA,
                Ator.vendedor, UUID.randomUUID(), NOW, null, true, null, null,
                ResumoAuditoria.ofCnpj("11.***.***/0001-**"));
        AuditEvent operator = new AuditEvent(UUID.randomUUID(), UUID.randomUUID(), TipoAuditoria.LINK_ATIVO_ALTERADO,
                Ator.operador, null, NOW, "192.0.2.10", false, null, TokenSlot.PREVIOUS,
                new ResumoAuditoria(null, null, null, "loja-exemplo", null, false));

        audit.saveAll(List.of(seller, operator));

        assertThat(audit.findById(seller.id())).contains(seller);
        assertThat(audit.findById(operator.id())).contains(operator);
        Document raw = raw(AuditEvent.COLLECTION, seller.id());
        assertThat(raw).containsEntry("ip_desconhecido", true).containsEntry("ator", "vendedor")
                .containsKeys("workspace_id", "tipo", "case_id", "ocorrido_em", "resumo")
                .doesNotContainKeys("session_hash", "token_slot", "client_ip");
        assertThat(raw.get("resumo", Document.class)).containsEntry("cnpj_mascarado", "11.***.***/0001-**");
        assertThat(raw(AuditEvent.COLLECTION, operator.id())).containsEntry("token_slot", "PREVIOUS");
    }

    @Test
    void onlyOneUnprocessedEventPerCaseAndTypeIsKept() {
        UUID caseId = UUID.randomUUID();
        OutboxEvent first = pending(caseId);
        outbox.insert(first);

        assertThatThrownBy(() -> outbox.insert(pending(caseId))).isInstanceOf(DuplicateKeyException.class);

        outbox.save(new OutboxEvent(first.id(), first.tipo(), caseId, first.payloadCifrado(), first.criadoEm(), NOW,
                false, first.expiraEm()));
        OutboxEvent next = pending(caseId);
        outbox.insert(next);

        assertThat(outbox.findById(next.id())).contains(next);
        assertThat(raw(OutboxEvent.COLLECTION, first.id())).containsEntry("pendente", false)
                .containsKeys("processado_em", "payload_cifrado", "expira_em");
    }

    @Test
    void cacheAndOutboxExpireOnTheirExpiryField() {
        assertThat(ttlSeconds(CompanyCacheEntry.COLLECTION, "expira_em")).isZero();
        assertThat(ttlSeconds(OutboxEvent.COLLECTION, "expira_em")).isZero();
    }

    @Test
    void cacheEntriesRoundTripFoundAndNotFound() {
        CompanyCacheEntry found = CompanyCacheEntry.found(cnpj(), new ProjecaoEmpresa("Loja Exemplo Ltda",
                "Sao Paulo", "SP", "Comercio varejista"), NOW, NOW.plus(30, ChronoUnit.DAYS));
        CompanyCacheEntry missing = CompanyCacheEntry.notFound(cnpj(), NOW, NOW.plus(1, ChronoUnit.DAYS));

        cache.saveAll(List.of(found, missing));

        assertThat(cache.findById(found.cnpj())).contains(found);
        assertThat(cache.findById(missing.cnpj())).contains(missing);
        assertThat(raw(CompanyCacheEntry.COLLECTION, missing.cnpj())).containsEntry("nao_encontrada", true)
                .containsKeys("consultado_em", "expira_em");
    }

    @Test
    void theLedgerIsKeyedByMonth() {
        String month = CreditLedger.idFor(YearMonth.of(2026, 11));
        CreditLedger entry = new CreditLedger(month, 7, 1, 6, 3, 2, 2);

        ledger.save(entry);

        assertThat(month).isEqualTo("2026-11");
        assertThat(ledger.findById(month)).contains(entry);
    }

    private OutboxEvent pending(UUID caseId) {
        return new OutboxEvent(UUID.randomUUID(), TipoOutbox.CONTATO_ALTERADO, caseId, new byte[] {9, 8, 7}, NOW,
                NOW.plus(30, ChronoUnit.DAYS));
    }

    private Document raw(String collection, Object id) {
        return mongoTemplate.getCollection(collection).find(new Document("_id", id)).first();
    }

    /** The TTL of the index on {@code field}, or -1 when it has none. */
    private long ttlSeconds(String collection, String field) {
        return mongoTemplate.getCollection(collection).listIndexes().into(new ArrayList<>()).stream()
                .filter(index -> index.get("key", Document.class).containsKey(field))
                .map(index -> index.get("expireAfterSeconds", Number.class))
                .filter(Objects::nonNull).mapToLong(Number::longValue).findFirst().orElse(-1);
    }

    private static String cnpj() {
        return String.format("%014d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000_000_000L));
    }
}
