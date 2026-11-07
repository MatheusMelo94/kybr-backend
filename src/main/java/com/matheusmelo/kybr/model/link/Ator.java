package com.matheusmelo.kybr.model.link;

/** Who caused an audit event. The constants are the wire values. */
public enum Ator {
    /** The seller, through the link. */
    vendedor,
    /** An operator, through the admin endpoint. */
    operador,
    /** The application itself. */
    sistema
}
