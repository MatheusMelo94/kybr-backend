package com.matheusmelo.kybr.model.workspace;

/** The condition a policy rule covers. The constants are the wire values, in the panel's order. */
public enum Chave {
    /** The company's registration status is irregular. */
    situacao,
    /** The company opened recently (fewer than the rule's {@code dias}). */
    recente,
    /** The responsible person doesn't match the partner list. */
    qsa,
    cnae,
    /** State registration. */
    ie,
    /** The company is a micro-entrepreneur (MEI). */
    mei,
    /** No other rule fired. */
    ok
}
