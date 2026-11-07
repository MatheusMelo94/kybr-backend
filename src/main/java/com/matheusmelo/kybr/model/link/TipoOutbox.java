package com.matheusmelo.kybr.model.link;

/** What an outbox event asks a later sender to do. */
public enum TipoOutbox {
    /** Tell the previous contact address that it was replaced. */
    CONTATO_ALTERADO
}
