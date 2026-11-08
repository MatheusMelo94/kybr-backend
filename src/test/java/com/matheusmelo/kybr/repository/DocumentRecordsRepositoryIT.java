package com.matheusmelo.kybr.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.config.IndexBootstrap;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.DocumentRequest;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.CaseEvent;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.EstadoRetencao;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.OrigemRetencao;
import com.matheusmelo.kybr.model.link.OutboxEvent;
import com.matheusmelo.kybr.model.link.ResultadoEnvio;
import com.matheusmelo.kybr.model.link.Retencao;
import com.matheusmelo.kybr.model.link.TipoEventoCaso;
import com.matheusmelo.kybr.model.link.TipoOutbox;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.TestTokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

/**
 * Stored documents, document requests and case events, and the document, token-revocation,
 * retention and delivery fields on cases and outbox events: snake_case, indexes, and older records
 * that lack the new fields.
 */
class DocumentRecordsRepositoryIT extends MongoIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentRequestRepository documentRequests;

    @Autowired
    private CaseEventRepository caseEvents;

    @Autowired
    private LinkCaseRepository cases;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private IndexBootstrap indexBootstrap;

    @BeforeEach
    void ensureIndexes() {
        indexBootstrap.run(null);
    }

    @Test
    void aDocumentRoundTripsWithSnakeCaseFieldsAndNoFileName() {
        Document document = document(UUID.randomUUID(), DocumentoTipo.RESPONSAVEL, 1, sha());

        documents.insert(document);

        assertThat(documents.findById(document.id())).contains(document);
        org.bson.Document raw = raw(Document.COLLECTION, document.id());
        assertThat(raw).containsKeys("workspace_id", "case_id", "tipo", "formato", "pagina", "s3_key",
                "content_type", "bytes_original", "bytes_armazenados", "sha256_original", "sha256", "criado_em");
        assertThat(raw).containsEntry("formato", "fotos").containsEntry("tipo", "RESPONSAVEL");
        assertThat(raw.keySet()).noneMatch(key -> key.contains("name") || key.contains("nome"));
    }

    @Test
    void theSameOriginalFileTwiceForOneCaseAndTypeIsRefused() {
        UUID caseId = UUID.randomUUID();
        String original = sha();
        documents.insert(document(caseId, DocumentoTipo.RESPONSAVEL, 1, original));

        assertThatThrownBy(() -> documents.insert(document(caseId, DocumentoTipo.RESPONSAVEL, 2, original)))
                .isInstanceOf(DuplicateKeyException.class);
        documents.insert(document(caseId, DocumentoTipo.PROCURACAO, 1, original));
        documents.insert(document(UUID.randomUUID(), DocumentoTipo.RESPONSAVEL, 1, original));
    }

    @Test
    void aReceivedFileIsADueScanJobWithNoFinalObjectYet() {
        UUID caseId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Document received = Document.recebido(id, UUID.randomUUID(), caseId, null, DocumentoTipo.RESPONSAVEL,
                FormatoEnvio.pdf, 1, "application/pdf", 2048, sha(), NOW, "w/" + caseId + "/" + id, UUID.randomUUID());

        documents.insert(received);

        assertThat(documents.findById(id)).contains(received);
        org.bson.Document raw = raw(Document.COLLECTION, id);
        assertThat(raw).containsEntry("estado", "RECEBIDO").containsEntry("ativo", true)
                .containsEntry("tentativas", 0).containsKeys("quarentena_key", "envio_id", "proxima_tentativa");
        assertThat(raw.get("s3_key")).isNull();
        assertThat(raw.get("sha256")).isNull();
    }

    @Test
    void aRecordStoredBeforeTheScanJobExistedReadsAsAccepted() {
        UUID id = UUID.randomUUID();
        mongoTemplate.getCollection(Document.COLLECTION).insertOne(new org.bson.Document("_id", id)
                .append("case_id", UUID.randomUUID()).append("tipo", "RESPONSAVEL").append("pagina", 1)
                .append("bytes_original", 10L).append("bytes_armazenados", 10L).append("s3_key", "w/c/" + id));

        Document read = documents.findById(id).orElseThrow();

        assertThat(read.estado()).isNull();
        assertThat(read.estadoOuAceito()).isEqualTo(EstadoDocumento.ACEITO);
        assertThat(read.tentativas()).isZero();
    }

    @Test
    void aRefusedFileNoLongerBlocksTheSameBytesFromBeingSentAgain() {
        UUID caseId = UUID.randomUUID();
        String original = sha();
        Document refused = document(caseId, DocumentoTipo.RESPONSAVEL, 1, original);
        documents.insert(refused);
        mongoTemplate.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(refused.id())),
                new org.springframework.data.mongodb.core.query.Update().set(Document.ESTADO, EstadoDocumento.RECUSADO)
                        .unset(Document.ATIVO).unset(Document.STORAGE_KEY), Document.class);

        documents.insert(document(caseId, DocumentoTipo.RESPONSAVEL, 2, original));

        assertThatThrownBy(() -> documents.insert(document(caseId, DocumentoTipo.RESPONSAVEL, 3, original)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void manyReceivedFilesHaveNoFinalKeyWithoutColliding() {
        UUID caseId = UUID.randomUUID();
        for (int i = 1; i <= 3; i++) {
            UUID id = UUID.randomUUID();
            documents.insert(Document.recebido(id, UUID.randomUUID(), caseId, null, DocumentoTipo.RESPONSAVEL,
                    FormatoEnvio.fotos, i, "image/jpeg", 10, sha(), NOW, "w/" + caseId + "/" + id, UUID.randomUUID()));
        }

        assertThat(documents.findByCaseId(caseId)).hasSize(3);
    }

    @Test
    void documentsAreFoundPerCaseAndTypeInPageOrderAndByStorageKey() {
        UUID caseId = UUID.randomUUID();
        Document second = document(caseId, DocumentoTipo.RESPONSAVEL, 2, sha());
        Document first = document(caseId, DocumentoTipo.RESPONSAVEL, 1, sha());
        Document other = document(caseId, DocumentoTipo.CARTAO_CNPJ, 1, sha());
        documents.insert(List.of(second, first, other));

        assertThat(documents.findByCaseIdAndTipoOrderByPaginaAsc(caseId, DocumentoTipo.RESPONSAVEL))
                .containsExactly(first, second);
        assertThat(documents.findByCaseId(caseId)).hasSize(3);
        assertThat(documents.existsByCaseIdAndTipoAndSha256Original(caseId, DocumentoTipo.RESPONSAVEL,
                first.sha256Original())).isTrue();
        assertThat(documents.existsByStorageKey(other.storageKey())).isTrue();
        assertThat(documents.existsByStorageKey("w/c/" + UUID.randomUUID())).isFalse();
        assertThat(documents.deleteByCaseId(caseId)).isEqualTo(3);
        assertThat(documents.findByCaseId(caseId)).isEmpty();
    }

    @Test
    void documentsAreIndexedPerPageByStoredHashUniquelyByOriginalHashAndByKey() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(Document.COLLECTION).getIndexInfo();

        assertThat(indexes).anyMatch(index -> keys(index).equals(List.of("case_id", "tipo", "pagina")));
        assertThat(indexes).anyMatch(index -> keys(index).equals(List.of("case_id", "sha256")));
        assertThat(indexes).anyMatch(index -> index.isUnique()
                && keys(index).equals(List.of("case_id", "tipo", "sha256_original")));
        assertThat(indexes).anyMatch(index -> index.isUnique() && keys(index).equals(List.of("s3_key")));
        assertThat(indexes).anyMatch(index -> index.isUnique() && keys(index).equals(List.of("quarentena_key")));
        assertThat(indexes).anyMatch(index -> keys(index).equals(List.of("estado", "proxima_tentativa")));
    }

    @Test
    void aDocumentRequestRoundTripsAndItsTokenHashIsUnique() {
        String tokenHash = "sha256:" + TestTokens.caseToken();
        DocumentRequest request = new DocumentRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                tokenHash, List.of(DocumentoTipo.PROCURACAO, DocumentoTipo.COMPROVANTE_ENDERECO), NOW,
                NOW.plus(30, ChronoUnit.DAYS), null, null);

        documentRequests.insert(request);

        assertThat(documentRequests.findByTokenHash(tokenHash)).contains(request);
        assertThat(documentRequests.findByCaseId(request.caseId())).containsExactly(request);
        assertThat(raw(DocumentRequest.COLLECTION, request.id())).containsKeys("workspace_id", "case_id",
                "token_hash", "tipos", "criado_em", "expira_em").doesNotContainKeys("revogado_em", "encerrado_em");
        assertThatThrownBy(() -> documentRequests.insert(new DocumentRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), tokenHash, List.of(DocumentoTipo.PROCURACAO), NOW, NOW, null, null)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aCaseEventRoundTripsAndEventsAreIndexedPerCaseInTimeOrder() {
        CaseEvent event = new CaseEvent(UUID.randomUUID(), UUID.randomUUID(), TipoEventoCaso.documento_recebido,
                new CaseEvent.Dados(DocumentoTipo.CARTAO_CNPJ), NOW);

        caseEvents.insert(event);

        assertThat(caseEvents.findByCaseIdOrderByOcorridoEmAsc(event.caseId())).containsExactly(event);
        org.bson.Document raw = raw(CaseEvent.COLLECTION, event.id());
        assertThat(raw).containsEntry("tipo", "documento_recebido").containsKeys("case_id", "dados", "ocorrido_em");
        assertThat(raw.get("dados", org.bson.Document.class)).containsEntry("tipo", "CARTAO_CNPJ");
        assertThat(mongoTemplate.indexOps(CaseEvent.COLLECTION).getIndexInfo())
                .anyMatch(index -> keys(index).equals(List.of("case_id", "ocorrido_em")));
    }

    @Test
    void aCaseKeepsItsDocumentCountersRevocationAndRetentionState() {
        LinkCase linkCase = linkCase();
        LinkCase stored = new LinkCase(linkCase.id(), linkCase.workspaceId(), linkCase.tokenHash(), linkCase.estado(),
                linkCase.criadoEm(), linkCase.cnpj(), linkCase.responsavel(), null, null, null, null, null, null, null,
                null, null, null, null, NOW.plus(365, ChronoUnit.DAYS),
                Map.of(DocumentoTipo.RESPONSAVEL, new DocumentosTipo(FormatoEnvio.fotos, 2, 2048L)), 2, NOW,
                new Retencao(EstadoRetencao.EM_ANDAMENTO, "owner-1", NOW.plusSeconds(300), null),
                OrigemRetencao.BACKSTOP);

        cases.insert(stored);

        assertThat(cases.findById(stored.id())).contains(stored);
        org.bson.Document raw = raw(LinkCase.COLLECTION, stored.id());
        assertThat(raw).containsEntry("arquivos_total", 2).containsEntry("retencao_origem", "BACKSTOP")
                .containsKeys("token_revogado_em", "retencao_ate", "documentos", "retencao");
        assertThat(raw.get("documentos", org.bson.Document.class).get("RESPONSAVEL", org.bson.Document.class))
                .containsEntry("formato", "fotos").containsEntry("arquivos", 2).containsEntry("bytes", 2048L);
        assertThat(raw.get("retencao", org.bson.Document.class)).containsEntry("estado", "EM_ANDAMENTO")
                .containsKeys("lease_owner", "lease_until");
    }

    @Test
    void aCaseStoredBeforeDocumentsExistedStillReads() {
        UUID id = UUID.randomUUID();
        mongoTemplate.getCollection(LinkCase.COLLECTION).insertOne(new org.bson.Document("_id", id)
                .append("workspace_id", UUID.randomUUID()).append("token_hash", "sha256:" + TestTokens.caseToken())
                .append("estado", "processing").append("criado_em", java.util.Date.from(NOW))
                .append("cnpj", "11222333000181").append("responsavel", "Maria Exemplo"));

        LinkCase legacy = cases.findById(id).orElseThrow();

        assertThat(legacy.documentos()).isEmpty();
        assertThat(legacy.arquivosTotal()).isZero();
        assertThat(legacy.tokenRevogadoEm()).isNull();
        assertThat(legacy.retencao()).isNull();
        assertThat(legacy.retencaoOrigem()).isNull();
    }

    @Test
    void casesAreIndexedForTheRetentionClaim() {
        assertThat(mongoTemplate.indexOps(LinkCase.COLLECTION).getIndexInfo())
                .anyMatch(index -> keys(index).equals(List.of("retencao_ate", "retencao.estado")));
    }

    @Test
    void anOutboxEventKeepsItsDeliveryStateAndLeavesTheQueueWhenItFails() {
        OutboxEvent created = new OutboxEvent(UUID.randomUUID(), TipoOutbox.CONTATO_ALTERADO, UUID.randomUUID(),
                new byte[] {1, 2}, NOW, NOW.plus(30, ChronoUnit.DAYS));
        assertThat(created.pendente()).isTrue();
        assertThat(created.tentativas()).isZero();
        assertThat(created.proximaTentativa()).as("due as soon as it exists").isEqualTo(NOW);

        OutboxEvent failed = new OutboxEvent(created.id(), created.tipo(), created.caseId(), created.payloadCifrado(),
                created.criadoEm(), null, true, created.expiraEm(), 3, NOW.plusSeconds(60), "owner-1",
                NOW.plusSeconds(60), NOW, null);
        assertThat(failed.pendente()).as("a failed event is out of the queue").isFalse();
        outbox.insert(failed);

        assertThat(outbox.findById(failed.id())).contains(failed);
        assertThat(raw(OutboxEvent.COLLECTION, failed.id())).containsEntry("pendente", false)
                .containsEntry("tentativas", 3)
                .containsKeys("proxima_tentativa", "lease_owner", "lease_until", "falhou_em");
        outbox.insert(new OutboxEvent(UUID.randomUUID(), created.tipo(), created.caseId(), new byte[] {3}, NOW,
                NOW.plus(30, ChronoUnit.DAYS)));

        OutboxEvent capped = new OutboxEvent(UUID.randomUUID(), TipoOutbox.CONTATO_ALTERADO, UUID.randomUUID(),
                new byte[] {4}, NOW, NOW, false, NOW, 1, null, null, null, null, ResultadoEnvio.limitado);
        outbox.insert(capped);
        assertThat(raw(OutboxEvent.COLLECTION, capped.id())).containsEntry("resultado_envio", "limitado");
        assertThat(outbox.deleteByCaseId(created.caseId())).isEqualTo(2);
    }

    @Test
    void anOutboxEventStoredBeforeDeliveryStateExistedStillReads() {
        UUID id = UUID.randomUUID();
        mongoTemplate.getCollection(OutboxEvent.COLLECTION).insertOne(new org.bson.Document("_id", id)
                .append("tipo", "CONTATO_ALTERADO").append("case_id", UUID.randomUUID())
                .append("payload_cifrado", new byte[] {1}).append("criado_em", java.util.Date.from(NOW))
                .append("pendente", true).append("expira_em", java.util.Date.from(NOW.plus(30, ChronoUnit.DAYS))));

        OutboxEvent legacy = outbox.findById(id).orElseThrow();

        assertThat(legacy.pendente()).isTrue();
        assertThat(legacy.tentativas()).isZero();
        assertThat(legacy.proximaTentativa()).isNull();
        assertThat(legacy.falhouEm()).isNull();
        assertThat(legacy.resultadoEnvio()).isNull();
    }

    @Test
    void outboxEventsAreIndexedForTheDispatcherClaim() {
        assertThat(mongoTemplate.indexOps(OutboxEvent.COLLECTION).getIndexInfo())
                .anyMatch(index -> keys(index).equals(List.of("pendente", "proxima_tentativa")));
    }

    private static Document document(UUID caseId, DocumentoTipo tipo, int pagina, String sha256Original) {
        UUID id = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        return new Document(id, workspaceId, caseId, null, tipo, FormatoEnvio.fotos, pagina,
                workspaceId + "/" + caseId + "/" + id, "image/jpeg", 2048L, 1024L, sha256Original, sha(), NOW);
    }

    private static LinkCase linkCase() {
        return new LinkCase(UUID.randomUUID(), UUID.randomUUID(), "sha256:" + TestTokens.caseToken(),
                EstadoCaso.pending, NOW, "11222333000181", "Maria Exemplo", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static String sha() {
        return java.util.HexFormat.of().formatHex(java.nio.ByteBuffer.allocate(32)
                .putLong(UUID.randomUUID().getMostSignificantBits()).putLong(UUID.randomUUID().getLeastSignificantBits())
                .array());
    }

    private org.bson.Document raw(String collection, UUID id) {
        return mongoTemplate.getCollection(collection).find(new org.bson.Document("_id", id)).first();
    }

    private static List<String> keys(IndexInfo index) {
        return index.getIndexFields().stream().map(field -> field.getKey()).toList();
    }
}
