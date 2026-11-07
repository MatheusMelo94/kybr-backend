package com.matheusmelo.kybr.model.link;

/** Why a case went to manual review instead of getting an automatic verdict. */
public enum MotivoRevisao {
    /** The monthly provider credits are spent. */
    ORCAMENTO,
    /** A daily verification budget (global or this link's) is spent. */
    ORCAMENTO_DIARIO,
    /** An operator switched provider spend off. */
    GASTO_DESLIGADO,
    /** This link reached its daily cap of "no data found" outcomes. */
    LIMITE_SEM_DADOS,
    /** The provider kept failing after every retry. */
    PROVEDOR_INDISPONIVEL,
    /** The provider doesn't support this CNPJ. */
    PROVEDOR_NAO_SUPORTA,
    /**
     * The provider's record lacks a field the verdict needs, or has one that can't be right (an
     * opening date in the future).
     */
    DADOS_INCOMPLETOS,
    /** Processing kept failing unexpectedly until the attempts ran out. */
    FALHA_PROCESSAMENTO
}
