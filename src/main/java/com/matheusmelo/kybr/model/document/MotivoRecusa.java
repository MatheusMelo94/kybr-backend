package com.matheusmelo.kybr.model.document;

/**
 * Why a received file was refused, as the seller sees it. Generic on purpose: a detection is never
 * called malware; the audit keeps the scanner's signature name.
 */
public enum MotivoRecusa {
    /** The file isn't accepted: the seller sends a different one. */
    CONTEUDO_NAO_PERMITIDO,
    /** The file is damaged or unreadable (it can't be decoded): the seller sends a new copy or photo. */
    ARQUIVO_INVALIDO,
    /** The file couldn't be verified in time: the seller may send it again. */
    VERIFICACAO_FALHOU
}
