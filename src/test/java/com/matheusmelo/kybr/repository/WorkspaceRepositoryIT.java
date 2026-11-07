package com.matheusmelo.kybr.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.config.IndexBootstrap;
import com.matheusmelo.kybr.model.workspace.Acao;
import com.matheusmelo.kybr.model.workspace.Chave;
import com.matheusmelo.kybr.model.workspace.ContatoVendedor;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Plano;
import com.matheusmelo.kybr.model.workspace.Politica;
import com.matheusmelo.kybr.model.workspace.PoliticaRegra;
import com.matheusmelo.kybr.model.workspace.RuntimeFlags;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Workspaces and the global runtime flags on the replica set: snake_case documents, unique slugs. */
class WorkspaceRepositoryIT extends MongoIntegrationTest {

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RuntimeFlagsRepository flags;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private IndexBootstrap indexBootstrap;

    /** The global flags as they were before the test: the database is shared with every other test. */
    private Optional<RuntimeFlags> globalBefore;

    @BeforeEach
    void ensureIndexes() {
        indexBootstrap.run(null);
        globalBefore = flags.findById(RuntimeFlags.GLOBAL_ID);
    }

    @AfterEach
    void restoreTheGlobalFlags() {
        globalBefore.ifPresentOrElse(flags::save, () -> flags.deleteById(RuntimeFlags.GLOBAL_ID));
    }

    @Test
    void aWorkspaceRoundTripsWithSnakeCaseFields() {
        Workspace workspace = workspace("loja-" + UUID.randomUUID().toString().substring(0, 8));

        workspaces.save(workspace);

        assertThat(workspaces.findById(workspace.id())).contains(workspace);
        assertThat(workspaces.findBySlug(workspace.slug())).contains(workspace);
        Document raw = mongoTemplate.getCollection(Workspace.COLLECTION)
                .find(new Document("_id", workspace.id())).first();
        assertThat(raw).containsKeys("nome", "slug", "categoria", "contato_vendedor", "plano", "link_ativo",
                "autofill_ativo", "politica", "documentos_habilitados", "criado_em");
        assertThat(raw.get("contato_vendedor", Document.class)).containsEntry("tipo", "email");
        assertThat(raw.get("plano", Document.class)).containsEntry("tipo", "interno");
        Document regra = raw.get("politica", Document.class).getList("regras", Document.class).getFirst();
        assertThat(regra).containsEntry("chave", "recente").containsEntry("acao", "approve_limit")
                .containsEntry("dias", 180).containsKey("acoes_permitidas");
    }

    @Test
    void aSecondWorkspaceWithTheSameSlugIsRefused() {
        String slug = "loja-" + UUID.randomUUID().toString().substring(0, 8);
        workspaces.insert(workspace(slug));

        assertThatThrownBy(() -> workspaces.insert(workspace(slug))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void theGlobalRuntimeFlagsRoundTrip() {
        RuntimeFlags global = new RuntimeFlags(RuntimeFlags.GLOBAL_ID, false, true);

        flags.save(global);

        assertThat(flags.findById(RuntimeFlags.GLOBAL_ID)).contains(global);
        Document raw = mongoTemplate.getCollection(RuntimeFlags.COLLECTION)
                .find(new Document("_id", RuntimeFlags.GLOBAL_ID)).first();
        assertThat(raw).containsEntry("autofill_ativo", false).containsEntry("gasto_provedor_ativo", true);
    }

    private static Workspace workspace(String slug) {
        Politica politica = new Politica(1, List.of(
                new PoliticaRegra(Chave.recente, Acao.approve_limit, List.of(Acao.approve, Acao.approve_limit), 180),
                new PoliticaRegra(Chave.ok, Acao.auto, List.of(Acao.auto, Acao.review), null)));
        return new Workspace(UUID.randomUUID(), "Loja Exemplo", slug, "geral",
                new ContatoVendedor(ContatoVendedor.Tipo.email, "contato@example.com"), new Plano(Plano.Tipo.interno),
                true, true, politica, List.of(DocumentoTipo.CARTAO_CNPJ, DocumentoTipo.RESPONSAVEL),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }
}
