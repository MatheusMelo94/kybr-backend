package com.matheusmelo.kybr.dto.link;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * The seller-facing result of a case. It never carries raw checks, partner names, policy names or
 * verdict words. Absent fields are omitted.
 *
 * @param razaoSocial the company name, any CPF masked; absent with {@code variante}
 * @param responsavel the responsible person as the seller typed it (NFC-normalised)
 * @param consultadoEm when the company data was fetched, ISO 8601 with offset; present whenever
 *     there is company data, absent with {@code variante}
 * @param variante {@code DADOS_NAO_ENCONTRADOS} when no company data was found
 * @param prazoRevisao when a pending case is due to be decided; always present on pending
 * @param emailContatoMascarado the seller's contact e-mail, masked, once saved
 * @param confirmacoes on approved
 * @param itens on pending
 * @param motivo on blocked
 */
@Schema(description = "The seller-facing result: `confirmacoes` on `approved`, `itens` on `pending`, `motivo` on "
        + "`blocked`. Never raw checks, partner names, policy names or verdict words.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultadoPublico(
        @Schema(description = "The company name, any CPF masked as `***.***.***-**`. Absent with `variante`.")
        String razaoSocial,
        @Schema(description = "The responsible person as the seller typed it, NFC-normalised and trimmed.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String responsavel,
        @Schema(description = "When the company data was fetched, ISO 8601 with an offset. Present whenever there "
                + "is company data; absent with `variante`, or on `processing` before a result.",
                example = "2026-11-07T14:03:11Z")
        Instant consultadoEm,
        @Schema(description = "`DADOS_NAO_ENCONTRADOS` when no company data was found; the case then goes to "
                + "human review and keeps the variant after it is decided.")
        Variante variante,
        @Schema(description = "When a pending case is due to be decided, ISO 8601 with an offset. Always present on "
                + "`pending`.", example = "2026-11-09T14:03:11Z")
        Instant prazoRevisao,
        @Schema(description = "The seller's contact e-mail, masked, once saved: the first character of the local "
                + "part, then `•••` (a one-character local part becomes just `•••`).",
                example = "j•••@empresa.com.br")
        String emailContatoMascarado,
        List<Confirmacao> confirmacoes,
        List<Item> itens,
        Motivo motivo) {

    public ResultadoPublico {
        confirmacoes = confirmacoes == null ? null : List.copyOf(confirmacoes);
        itens = itens == null ? null : List.copyOf(itens);
    }

    public enum Variante {
        DADOS_NAO_ENCONTRADOS
    }

    /** An irregular registration status. */
    public enum Situacao {
        inapta,
        suspensa,
        baixada,
        nula
    }

    public enum DocumentoTipo {
        CONTRATO_SOCIAL_OU_CCMEI,
        PROCURACAO,
        COMPROVANTE_ENDERECO,
        CARTAO_CNPJ,
        RESPONSAVEL
    }

    /** @param uf required with {@code IE_ATIVA} */
    @Schema(description = "What was confirmed on an approved case. The rows and their order vary.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Confirmacao(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CodigoConfirmacao codigo,
            @Schema(description = "The state of the active registration; present with `IE_ATIVA`.",
                    pattern = "^[A-Z]{2}$") String uf) {
    }

    public enum CodigoConfirmacao {
        SITUACAO_ATIVA,
        IE_ATIVA,
        RESPONSAVEL_CONFERE
    }

    /**
     * A pending item. Document items carry {@code documento_tipo} and {@code documentos_enviados};
     * {@code SITUACAO_IRREGULAR} carries {@code situacao}; {@code EMPRESA_RECENTE} carries
     * {@code dias} (at least 1).
     *
     * @param urlRegularizacao an {@code https:} page on gov.br or a gov.br subdomain
     */
    @Schema(description = "What is needed or under review on a pending case.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CodigoItem codigo,
            @Schema(description = "Present on document items.") DocumentoTipo documentoTipo,
            @Schema(description = "Present on document items.", minimum = "0") Integer documentosEnviados,
            @Schema(description = "Present on `SITUACAO_IRREGULAR`.") Situacao situacao,
            @Schema(description = "Present on `EMPRESA_RECENTE`: days since the company opened.", minimum = "1")
            Integer dias,
            @Schema(description = "An absolute `https:` URL on gov.br or a gov.br subdomain.")
            String urlRegularizacao) {
    }

    public enum CodigoItem {
        DOCUMENTO_RESPONSAVEL,
        DOCUMENTO_SOLICITADO,
        IE_NAO_ENCONTRADA,
        IE_SUSPENSA,
        SITUACAO_IRREGULAR,
        ATIVIDADE_FORA_DA_CATEGORIA,
        EMPRESA_RECENTE,
        MEI
    }

    /** @param urlRegularizacao an {@code https:} page on gov.br or a gov.br subdomain */
    @Schema(description = "Why a case was blocked. A decision made by a person shows as `GENERICO`.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Motivo(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CodigoMotivo codigo,
            @Schema(description = "Present on `SITUACAO_IRREGULAR`.") Situacao situacao,
            @Schema(description = "An absolute `https:` URL on gov.br or a gov.br subdomain.")
            String urlRegularizacao) {
    }

    public enum CodigoMotivo {
        SITUACAO_IRREGULAR,
        RESPONSAVEL_NAO_SOCIO,
        EMPRESA_RECENTE,
        ATIVIDADE_FORA_DA_CATEGORIA,
        IE_NAO_ENCONTRADA,
        IE_SUSPENSA,
        MEI,
        GENERICO
    }
}
