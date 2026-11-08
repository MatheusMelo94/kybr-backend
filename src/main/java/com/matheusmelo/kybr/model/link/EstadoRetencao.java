package com.matheusmelo.kybr.model.link;

/** Where a case's retention run stands. */
public enum EstadoRetencao {
    PENDENTE,
    /** Claimed: the token is revoked and deletion is under way (or will be re-run). */
    EM_ANDAMENTO,
    CONCLUIDA
}
