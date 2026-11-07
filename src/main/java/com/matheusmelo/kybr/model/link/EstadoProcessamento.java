package com.matheusmelo.kybr.model.link;

/** Where a case's background verification stands. The case document is the job. */
public enum EstadoProcessamento {
    /** Waiting to be claimed, at or after {@code proxima_tentativa}. */
    PENDENTE,
    /** Claimed by a worker until {@code lease_until}. */
    EM_ANDAMENTO,
    /** A verdict was written. */
    CONCLUIDO,
    /** Waiting for a human reviewer; {@code motivo_revisao} says why. */
    REVISAO_MANUAL
}
