package com.matheusmelo.kybr.model.link;

/** A seller's standing in a workspace. The constants are the wire values. */
public enum StatusSeller {
    /** A verification is running or waiting for review. */
    em_verificacao,
    aprovado,
    revisao,
    bloqueado,
    /** Monitoring noticed a change since the last decision. */
    mudou
}
