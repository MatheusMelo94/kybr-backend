package com.matheusmelo.kybr.support.link;

import com.matheusmelo.kybr.model.workspace.Acao;
import com.matheusmelo.kybr.model.workspace.Chave;
import com.matheusmelo.kybr.model.workspace.ContatoVendedor;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Plano;
import com.matheusmelo.kybr.model.workspace.Politica;
import com.matheusmelo.kybr.model.workspace.PoliticaRegra;
import com.matheusmelo.kybr.model.workspace.Workspace;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Workspaces with valid defaults: a unique slug, an e-mail contact, the internal plan, link and
 * autofill on, the panel's default policy (version 1) and every document type but the power of
 * attorney. Values are synthetic.
 */
public final class TestWorkspaces {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private TestWorkspaces() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The panel's default policy, version 1: a test copy, independent of production code. */
    public static Politica defaultPolicy() {
        return new Politica(1, List.of(
                new PoliticaRegra(Chave.situacao, Acao.block, List.of(Acao.review, Acao.block), null),
                new PoliticaRegra(Chave.recente, Acao.review,
                        List.of(Acao.approve, Acao.review, Acao.request, Acao.block), 180),
                new PoliticaRegra(Chave.qsa, Acao.request,
                        List.of(Acao.approve, Acao.review, Acao.request, Acao.block), null),
                new PoliticaRegra(Chave.cnae, Acao.review,
                        List.of(Acao.approve, Acao.approve_limit, Acao.review, Acao.block), null),
                new PoliticaRegra(Chave.ie, Acao.approve_limit,
                        List.of(Acao.approve, Acao.approve_limit, Acao.review, Acao.request, Acao.block), null),
                new PoliticaRegra(Chave.mei, Acao.approve_limit,
                        List.of(Acao.approve, Acao.approve_limit, Acao.review), null),
                new PoliticaRegra(Chave.ok, Acao.auto, List.of(Acao.auto, Acao.review), null)));
    }

    /** Every document type except the power of attorney. */
    public static List<DocumentoTipo> defaultDocuments() {
        return Arrays.stream(DocumentoTipo.values()).filter(tipo -> tipo != DocumentoTipo.PROCURACAO).toList();
    }

    public static final class Builder {
        private UUID id = UUID.randomUUID();
        private String nome = "Loja Exemplo";
        private String slug = "loja-exemplo-" + SEQUENCE.incrementAndGet() + "-"
                + UUID.randomUUID().toString().substring(0, 6);
        private String categoria = "geral";
        private ContatoVendedor contato = new ContatoVendedor(ContatoVendedor.Tipo.email, "contato@example.com");
        private Plano.Tipo plano = Plano.Tipo.interno;
        private boolean linkAtivo = true;
        private boolean autofillAtivo = true;
        private Politica politica = defaultPolicy();
        private List<DocumentoTipo> documentos = defaultDocuments();
        private Instant criadoEm = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        private Builder() {
        }

        public Builder id(UUID value) {
            id = value;
            return this;
        }

        public Builder nome(String value) {
            nome = value;
            return this;
        }

        public Builder slug(String value) {
            slug = value;
            return this;
        }

        public Builder categoria(String value) {
            categoria = value;
            return this;
        }

        /** Null for a workspace that isn't onboarded. */
        public Builder contato(ContatoVendedor value) {
            contato = value;
            return this;
        }

        public Builder contatoUrl(String url) {
            contato = new ContatoVendedor(ContatoVendedor.Tipo.url, url);
            return this;
        }

        public Builder plano(Plano.Tipo value) {
            plano = value;
            return this;
        }

        public Builder linkAtivo(boolean value) {
            linkAtivo = value;
            return this;
        }

        public Builder autofillAtivo(boolean value) {
            autofillAtivo = value;
            return this;
        }

        public Builder politica(Politica value) {
            politica = value;
            return this;
        }

        public Builder documentos(List<DocumentoTipo> value) {
            documentos = value;
            return this;
        }

        public Builder criadoEm(Instant value) {
            criadoEm = value;
            return this;
        }

        public Workspace build() {
            return new Workspace(id, nome, slug, categoria, contato, new Plano(plano), linkAtivo, autofillAtivo,
                    politica, documentos, criadoEm);
        }
    }
}
