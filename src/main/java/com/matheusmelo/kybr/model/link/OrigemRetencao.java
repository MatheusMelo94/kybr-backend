package com.matheusmelo.kybr.model.link;

/** Where a case's retention date came from. */
public enum OrigemRetencao {
    /** Computed from the seller's last activity on a case never decided; recomputed while it stays so. */
    BACKSTOP,
    /** Set when the case was decided; never recomputed. */
    DECISAO
}
