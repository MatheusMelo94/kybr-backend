package com.matheusmelo.kybr.model.link;

/** What the seller sees of a case. The constants are the wire values. */
public enum EstadoCaso {
    /** Being verified, or waiting for manual review ("em análise"). */
    processing,
    pending,
    approved,
    blocked
}
