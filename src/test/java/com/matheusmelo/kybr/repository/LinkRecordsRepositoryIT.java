package com.matheusmelo.kybr.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.config.IndexBootstrap;
import com.matheusmelo.kybr.model.link.Checagem;
import com.matheusmelo.kybr.model.link.ChaveChecagem;
import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.Contato;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.ModoConsulta;
import com.matheusmelo.kybr.model.link.OrigemSeller;
import com.matheusmelo.kybr.model.link.Processamento;
import com.matheusmelo.kybr.model.link.Regime;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.link.Revisao;
import com.matheusmelo.kybr.model.link.Seller;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import com.matheusmelo.kybr.model.link.SnapshotEmpresa;
import com.matheusmelo.kybr.model.link.StatusChecagem;
import com.matheusmelo.kybr.model.link.StatusSeller;
import com.matheusmelo.kybr.model.link.Verification;
import com.matheusmelo.kybr.model.link.Veredito;
import com.matheusmelo.kybr.model.workspace.Acao;
import com.matheusmelo.kybr.model.workspace.Chave;
import com.matheusmelo.kybr.model.workspace.Politica;
import com.matheusmelo.kybr.model.workspace.PoliticaRegra;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.TestTokens;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

/** Cases, sellers and verifications on the replica set: snake_case documents and their indexes. */
class LinkRecordsRepositoryIT extends MongoIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final Politica POLITICA = new Politica(3, List.of(
            new PoliticaRegra(Chave.situacao, Acao.block, List.of(Acao.review, Acao.block), null)));

    @Autowired
    private LinkCaseRepository cases;

    @Autowired
    private SellerRepository sellers;

    @Autowired
    private VerificationRepository verifications;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private IndexBootstrap indexBootstrap;

    @BeforeEach
    void ensureIndexes() {
        indexBootstrap.run(null);
    }

    @Test
    void aCaseRoundTripsWithItsPolicyCopyAndSnakeCaseFields() {
        LinkCase linkCase = linkCase(UUID.randomUUID(), TestTokens.caseToken());

        cases.save(linkCase);

        assertThat(cases.findByTokenHashAndWorkspaceId(linkCase.tokenHash(), linkCase.workspaceId()))
                .contains(linkCase);
        assertThat(cases.findByTokenHashAndWorkspaceId(linkCase.tokenHash(), UUID.randomUUID())).isEmpty();
        Document raw = raw(LinkCase.COLLECTION, linkCase.id());
        assertThat(raw).containsKeys("workspace_id", "token_hash", "estado", "criado_em", "cnpj", "responsavel",
                "ref", "ext_id", "politica", "resultado", "contato", "processamento", "prazo_revisao", "revisao",
                "idempotency_key");
        assertThat(raw).containsEntry("estado", "pending");
        assertThat(raw.get("politica", Document.class)).containsEntry("versao", 3);
        assertThat(raw.get("processamento", Document.class)).containsKeys("estado", "tentativas",
                "proxima_tentativa");
        assertThat(raw.get("contato", Document.class)).containsKeys("email_cifrado", "email_mascarado",
                "alterado_em");
        assertThat(raw.get("resultado", Document.class)).containsKeys("razao_social", "consultado_em",
                "prazo_revisao", "itens");
        assertThat(raw.get("revisao", Document.class)).containsKey("sla_ate");
    }

    @Test
    void aSecondCaseWithTheSameTokenHashIsRefused() {
        String token = TestTokens.caseToken();
        cases.insert(linkCase(UUID.randomUUID(), token));

        assertThatThrownBy(() -> cases.insert(linkCase(UUID.randomUUID(), token)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void casesAreIndexedForTheWorkersClaimAndPerCompany() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(LinkCase.COLLECTION).getIndexInfo();

        assertThat(indexes).anyMatch(index -> keys(index).equals(
                List.of("processamento.estado", "processamento.proxima_tentativa")));
        assertThat(indexes).anyMatch(index -> keys(index).equals(List.of("workspace_id", "cnpj", "criado_em")));
        assertThat(indexes).anyMatch(index -> index.isUnique() && keys(index).equals(List.of("token_hash")));
    }

    @Test
    void aSecondSellerForTheSameWorkspaceAndCnpjIsRefused() {
        UUID workspaceId = UUID.randomUUID();
        Seller seller = seller(workspaceId);
        sellers.insert(seller);

        assertThat(sellers.findByWorkspaceIdAndCnpj(workspaceId, seller.cnpj())).contains(seller);
        assertThatThrownBy(() -> sellers.insert(seller(workspaceId))).isInstanceOf(DuplicateKeyException.class);
        sellers.insert(seller(UUID.randomUUID()));
    }

    @Test
    void aVerificationRoundTripsWithThePolicyVersion() {
        Verification verification = new Verification(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "11222333000181", ModoConsulta.tempo_real, NOW,
                new SnapshotEmpresa("Loja Exemplo Ltda", "Sao Paulo", "SP", "4781400", "Comercio varejista",
                        SituacaoCadastral.ativa, LocalDate.of(2020, 1, 15), Regime.OUTRO),
                List.of(new Checagem(ChaveChecagem.situacao, "Situacao", "Situacao cadastral", "ativa",
                        StatusChecagem.ok)),
                Veredito.aprovar, StatusChecagem.ok, List.of("Situacao ativa"), 3, false, NOW);

        verifications.save(verification);

        assertThat(verifications.findByWorkspaceIdAndCaseId(verification.workspaceId(), verification.caseId()))
                .containsExactly(verification);
        Document raw = raw(Verification.COLLECTION, verification.id());
        assertThat(raw).containsEntry("politica_versao", 3).containsEntry("modo", "tempo_real")
                .containsKeys("case_id", "consultado_em", "snapshot", "checagens", "aprovado_com_limite");
        assertThat(raw.get("snapshot", Document.class)).containsKeys("razao_social", "cnae_codigo",
                "data_abertura");
    }

    private Document raw(String collection, UUID id) {
        return mongoTemplate.getCollection(collection).find(new Document("_id", id)).first();
    }

    private static List<String> keys(IndexInfo index) {
        return index.getIndexFields().stream().map(field -> field.getKey()).toList();
    }

    private static LinkCase linkCase(UUID workspaceId, String token) {
        Resultado resultado = new Resultado("Loja Exemplo Ltda", NOW, null, NOW.plus(2, ChronoUnit.DAYS), null,
                List.of(ItemResultado.of(CodigoResultado.MEI)), null);
        return new LinkCase(UUID.randomUUID(), workspaceId, "sha256:" + token, EstadoCaso.pending, NOW,
                "11222333000181", "Maria Exemplo", "pedido-1", "ext-1", POLITICA, null, resultado, UUID.randomUUID(),
                new Contato(new byte[] {1, 2, 3}, "m•••@example.com", NOW), Processamento.pendente(NOW),
                NOW.plus(2, ChronoUnit.DAYS), new Revisao(NOW.plus(2, ChronoUnit.DAYS)), UUID.randomUUID().toString(),
                null, null, null, null, null, null);
    }

    private static Seller seller(UUID workspaceId) {
        return new Seller(UUID.randomUUID(), workspaceId, "11222333000181", OrigemSeller.link, "Loja Exemplo Ltda",
                StatusSeller.em_verificacao, null, null);
    }
}
