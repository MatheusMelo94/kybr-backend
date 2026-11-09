package com.matheusmelo.kybr.model.link;

/** How an outbox event's e-mail ended. */
public enum ResultadoEnvio {
    /** Handed to the provider. */
    enviado,
    /** Dropped by the per-address cap; never retried. */
    limitado
}
