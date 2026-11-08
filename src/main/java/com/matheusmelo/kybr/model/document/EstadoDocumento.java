package com.matheusmelo.kybr.model.document;

/**
 * Where a received file is in its verification: {@code RECEBIDO} (in quarantine, waiting for the scan
 * job), {@code VERIFICANDO} (claimed by a scan worker), then {@code ACEITO} (stored for good) or
 * {@code RECUSADO} (deleted, with a {@link MotivoRecusa}).
 */
public enum EstadoDocumento {
    RECEBIDO,
    VERIFICANDO,
    ACEITO,
    RECUSADO;

    /** Still being verified: neither accepted nor refused. */
    public boolean pendente() {
        return this == RECEBIDO || this == VERIFICANDO;
    }
}
