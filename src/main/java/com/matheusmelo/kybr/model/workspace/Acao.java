package com.matheusmelo.kybr.model.workspace;

/** What a policy rule does when it fires. The constants are the wire values, in the panel's order. */
public enum Acao {
    auto,
    approve,
    /** Approve, recording that the approval is limited. */
    approve_limit,
    /** Pending, with an informational item for a reviewer. */
    review,
    /** Pending, asking the seller for a document. */
    request,
    block
}
