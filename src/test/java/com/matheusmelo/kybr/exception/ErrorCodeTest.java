package com.matheusmelo.kybr.exception;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Pins every code to its status: the frontend contract codes plus the B0 framework codes. */
class ErrorCodeTest {

    private static final Map<String, Integer> EXPECTED = Map.ofEntries(
            // The frontend contract: every code the backend sends
            entry("LINK_NAO_ENCONTRADO", 404),
            entry("EMPRESA_NAO_ENCONTRADA", 404),
            entry("CNPJ_INVALIDO", 422), // default; 400 on query/path via LocationStatusTable
            entry("RESPONSAVEL_INVALIDO", 422),
            entry("LIMITE_CONSULTAS", 429),
            entry("LIMITE_CNPJ", 429),
            entry("LIMITE_TENTATIVAS", 429),
            entry("CASO_NAO_ENCONTRADO", 404),
            entry("EMAIL_INVALIDO", 422),
            entry("ARQUIVO_GRANDE", 413),
            entry("FORMATO_INVALIDO", 415),
            entry("CASO_DECIDIDO", 409),
            entry("LIMITE_ARQUIVOS", 413),
            entry("AUTOFILL_DESATIVADO", 503),
            entry("SESSAO_EXPIRADA", 401),
            entry("TURNSTILE_REQUERIDO", 428),
            entry("TURNSTILE_INVALIDO", 400),
            entry("TESTE_INDISPONIVEL", 403),
            entry("TESTE_PAUSADO", 503),
            entry("CREDENCIAIS_INVALIDAS", 401),
            entry("EMAIL_NAO_CONFIRMADO", 403),
            entry("EMAIL_EM_USO", 409),
            entry("SENHA_FRACA", 422),
            entry("VALOR_INVALIDO", 422),
            entry("CODIGO_INCORRETO", 400),
            entry("CODIGO_EXPIRADO", 400),
            entry("TOKEN_INVALIDO", 400),
            entry("RECURSO_NAO_ENCONTRADO", 404),
            entry("LOTE_NAO_ENCONTRADO", 404),
            entry("SEM_SALDO", 402),
            entry("MUITAS_LINHAS", 422),
            entry("SEM_CNPJ_VALIDO", 422),
            entry("LOTE_EM_ANDAMENTO", 409),
            entry("CASO_JA_DECIDIDO", 409),
            entry("MOTIVO_OBRIGATORIO", 422),
            entry("DOCUMENTO_NAO_HABILITADO", 422),
            entry("SENHA_ATUAL_INCORRETA", 403),
            entry("REVERIFICACAO_EM_ANDAMENTO", 409),
            entry("POLITICA_DESATUALIZADA", 409),
            entry("SEM_DOCUMENTO_HABILITADO", 422),
            // Codes the frontend mock used, adopted by B0
            entry("BFF_KEY_AUSENTE", 403),
            entry("ERRO_INTERNO", 500),
            entry("INDISPONIVEL", 503),
            // New B0 contract codes
            entry("REQUISICAO_INVALIDA", 400),
            entry("METODO_NAO_PERMITIDO", 405),
            entry("FORMATO_NAO_ACEITO", 406), // errors are JSON whatever Accept says
            entry("MIDIA_NAO_SUPORTADA", 415),
            entry("REQUISICAO_GRANDE", 413));

    @Test
    void theEnumHoldsExactlyTheContractCodes() {
        assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name))
                .containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeHasItsContractStatus(ErrorCode code) {
        assertThat(code.status().value()).isEqualTo(EXPECTED.get(code.name()));
    }

    @Test
    void frontendSensitiveStatusesArePinned() {
        assertThat(ErrorCode.SENHA_ATUAL_INCORRETA.status().value()).as("never 401: not a session problem").isEqualTo(403);
        assertThat(ErrorCode.SEM_SALDO.status().value()).isEqualTo(402);
        assertThat(ErrorCode.FORMATO_NAO_ACEITO.status().value()).isEqualTo(406);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyMessageIsAFixedGenericEnglishSentence(ErrorCode code) {
        assertThat(code.message()).isNotBlank().matches("[A-Z][A-Za-z ,'-]+\\.");
    }

    @Test
    void errorCodeHasNoPerLocationFields() {
        assertThat(Arrays.stream(ErrorCode.class.getDeclaredFields())
                .filter(f -> !f.isEnumConstant() && !f.isSynthetic() && !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName))
                .containsExactlyInAnyOrder("status", "message");
    }
}
