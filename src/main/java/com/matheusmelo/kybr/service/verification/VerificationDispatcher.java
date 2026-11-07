package com.matheusmelo.kybr.service.verification;

import java.util.UUID;

/**
 * Hands a newly committed case to background verification. Call it only after the case's
 * transaction has committed, so a rollback never dispatches.
 */
public interface VerificationDispatcher {

    /**
     * Queues the case for verification on this machine. Never throws: when the queue is full or the
     * machine is shutting down, the case stays claimable and the recovery poller picks it up.
     */
    void submit(UUID caseId);
}
