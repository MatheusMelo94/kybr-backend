package com.matheusmelo.kybr.model.link;

/** What an audit event records. */
public enum TipoAuditoria {
    VERIFICACAO_INICIADA,
    CONTATO_ALTERADO,
    WORKSPACE_CRIADO,
    LINK_ATIVO_ALTERADO,
    AUTOFILL_ALTERADO,
    AUTOFILL_GLOBAL_ALTERADO,
    GASTO_PROVEDOR_ALTERADO,
    /** An operator sent the fixed test e-mail to an allowed address. */
    EMAIL_TESTE_ENVIADO,
    /** Retention deleted a case's documents and contact, and revoked its token. */
    RETENCAO_EXECUTADA,
    /** The orphan check deleted stored objects that had no record (a count, never a key). */
    ORFAOS_REMOVIDOS,
    /** Legacy (records written before the scan job existed): the seller's files were stored. */
    DOCUMENTO_RECEBIDO,
    /** Legacy (records written before the scan job existed): an upload was refused for a detection. */
    UPLOAD_RECUSADO_MALWARE,
    /** Files were received into quarantine for a document type, before their scan. */
    DOCUMENTO_ENVIADO,
    /** The scan job accepted a received file and stored it. */
    DOCUMENTO_ACEITO,
    /** The scan job refused a received file (a detection, or verification that kept failing). */
    DOCUMENTO_RECUSADO
}
