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
     * A pending item. Document items carry {@code documento_tipo} and {@code documentos_enviados},
     * plus, once a file was sent for the type, {@code formato_envio}, {@code situacao_envio} and
     * {@code documentos_recusados} ({@code motivo_recusa} with {@code recusado}); {@code SITUACAO_IRREGULAR}
     * carries {@code situacao}; {@code EMPRESA_RECENTE} carries {@code dias} (at least 1).
     *
     * @param urlRegularizacao an {@code https:} page on gov.br or a gov.br subdomain
     */
    @Schema(description = "What is needed or under review on a pending case.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CodigoItem codigo,
            @Schema(description = "Present on document items.") DocumentoTipo documentoTipo,
            @Schema(description = "Present on document items: the type's files accepted or still being checked "
                    + "(refused files aren't counted).", minimum = "0") Integer documentosEnviados,
            @Schema(description = "On document items, once a file that still counts was sent for the type: how the "
                    + "type is being sent. `pdf` takes no more files; `fotos` takes more pages.") FormatoEnvio formatoEnvio,
            @Schema(description = "On document items, once a file was sent for the type: `verificando` while any of "
                    + "its files is being checked (poll this read until it changes), then `recusado` when its latest "
                    + "upload had a refused file, otherwise `aceito`.") SituacaoEnvio situacaoEnvio,
            @Schema(description = "On document items, once a file was sent for the type: how many of its files were "
                    + "refused so far, across uploads (it stays after a later accepted upload).", minimum = "0")
            Integer documentosRecusados,
            @Schema(description = "With `situacao_envio=recusado`, about the latest upload: `CONTEUDO_NAO_PERMITIDO` "
                    + "(the file isn't accepted; send a different one), `ARQUIVO_INVALIDO` (it is damaged or unreadable; "
                    + "send a new copy or photo) or `VERIFICACAO_FALHOU` (it couldn't be checked; send it again).")
            MotivoRecusa motivoRecusa,
            @Schema(description = "Present on `SITUACAO_IRREGULAR`.") Situacao situacao,
            @Schema(description = "Present on `EMPRESA_RECENTE`: days since the company opened.", minimum = "1")
            Integer dias,
            @Schema(description = "An absolute `https:` URL on gov.br or a gov.br subdomain.")
            String urlRegularizacao) {
    }

    /**
     * A document type's verification: {@code verificando} while any of its files is being checked,
     * then {@code recusado} when its latest upload had a refused file, otherwise {@code aceito}.
     */
    public enum SituacaoEnvio {
        verificando,
        aceito,
        recusado
    }

    /**
     * Why a file was refused: {@code CONTEUDO_NAO_PERMITIDO} (the file isn't accepted; send a different
     * one), {@code ARQUIVO_INVALIDO} (it is damaged or unreadable; send a new copy or photo) or
     * {@code VERIFICACAO_FALHOU} (it couldn't be checked; send it again).
     */
    public enum MotivoRecusa {
        CONTEUDO_NAO_PERMITIDO,
        ARQUIVO_INVALIDO,
        VERIFICACAO_FALHOU
    }

    /** How a document type's files were sent: one PDF, or photos (pages). */
    public enum FormatoEnvio {
        pdf,
        fotos
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
