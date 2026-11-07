package com.matheusmelo.kybr.support.link;

import com.matheusmelo.kybr.model.link.Contato;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.Processamento;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.link.Revisao;
import com.matheusmelo.kybr.model.link.Variante;
import com.matheusmelo.kybr.model.workspace.Politica;
import com.matheusmelo.kybr.model.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Link cases with valid defaults for a workspace: a fresh 43-character token (32 random bytes,
 * base64url) and its hash, a synthetic CNPJ and responsible person, {@code processing} with
 * claimable processing state, and a copy of the workspace's policy. {@link Built} returns the raw
 * token beside the case, since the case stores only the hash.
 */
public final class TestCases {

    /** A valid, synthetic CNPJ. */
    public static final String CNPJ = "11222333000181";

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestCases() {
    }

    public static Builder builder(Workspace workspace) {
        return new Builder(workspace);
    }

    /** A fresh case token: 32 random bytes, base64url without padding (43 characters). */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The stored form of a token: SHA-256 of its UTF-8 bytes, lower-case hex, unkeyed. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A case and the raw token that addresses it. */
    public record Built(LinkCase linkCase, String token) {
    }

    public static final class Builder {
        private final UUID workspaceId;
        private UUID id = UUID.randomUUID();
        private String token = newToken();
        private EstadoCaso estado = EstadoCaso.processing;
        private Instant criadoEm = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        private String cnpj = CNPJ;
        private String responsavel = "Maria Exemplo";
        private String ref;
        private String extId;
        private Politica politica;
        private Variante variante;
        private Resultado resultado;
        private UUID verificacaoId;
        private Contato contato;
        private Processamento processamento;
        private Instant prazoRevisao;
        private Revisao revisao;
        private String idempotencyKey = UUID.randomUUID().toString();
        private Instant retencaoAte;

        private Builder(Workspace workspace) {
            workspaceId = workspace.id();
            politica = workspace.politica();
            processamento = Processamento.pendente(criadoEm);
        }

        public Builder id(UUID value) {
            id = value;
            return this;
        }

        public Builder token(String value) {
            token = value;
            return this;
        }

        public Builder estado(EstadoCaso value) {
            estado = value;
            return this;
        }

        public Builder criadoEm(Instant value) {
            criadoEm = value;
            return this;
        }

        public Builder cnpj(String value) {
            cnpj = value;
            return this;
        }

        public Builder responsavel(String value) {
            responsavel = value;
            return this;
        }

        public Builder ref(String value) {
            ref = value;
            return this;
        }

        public Builder extId(String value) {
            extId = value;
            return this;
        }

        public Builder politica(Politica value) {
            politica = value;
            return this;
        }

        public Builder variante(Variante value) {
            variante = value;
            return this;
        }

        public Builder resultado(Resultado value) {
            resultado = value;
            return this;
        }

        public Builder verificacaoId(UUID value) {
            verificacaoId = value;
            return this;
        }

        public Builder contato(Contato value) {
            contato = value;
            return this;
        }

        public Builder processamento(Processamento value) {
            processamento = value;
            return this;
        }

        public Builder prazoRevisao(Instant value) {
            prazoRevisao = value;
            return this;
        }

        public Builder revisao(Revisao value) {
            revisao = value;
            return this;
        }

        public Builder idempotencyKey(String value) {
            idempotencyKey = value;
            return this;
        }

        public Builder retencaoAte(Instant value) {
            retencaoAte = value;
            return this;
        }

        public Built build() {
            LinkCase linkCase = new LinkCase(id, workspaceId, hash(token), estado, criadoEm, cnpj, responsavel, ref,
                    extId, politica, variante, resultado, verificacaoId, contato, processamento, prazoRevisao, revisao,
                    idempotencyKey, retencaoAte);
            return new Built(linkCase, token);
        }
    }
}
