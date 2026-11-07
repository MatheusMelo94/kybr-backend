package com.matheusmelo.kybr.model.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The policy vocabulary matches the panel's, value for value and in order. */
class PolicyVocabularyTest {

    @Test
    void actionsAreExactlyThePanelsActions() {
        assertThat(Arrays.stream(Acao.values()).map(Enum::name))
                .containsExactly("auto", "approve", "approve_limit", "review", "request", "block");
    }

    @Test
    void ruleKeysAreExactlyThePanelsRuleKeys() {
        assertThat(Arrays.stream(Chave.values()).map(Enum::name))
                .containsExactly("situacao", "recente", "qsa", "cnae", "ie", "mei", "ok");
    }

    @Test
    void documentTypesAreExactlyThePanelsDocumentTypes() {
        assertThat(Arrays.stream(DocumentoTipo.values()).map(Enum::name)).containsExactly(
                "CONTRATO_SOCIAL_OU_CCMEI", "PROCURACAO", "COMPROVANTE_ENDERECO", "CARTAO_CNPJ", "RESPONSAVEL");
    }
}
