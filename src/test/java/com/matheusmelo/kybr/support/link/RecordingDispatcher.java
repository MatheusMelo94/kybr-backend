package com.matheusmelo.kybr.support.link;

import com.matheusmelo.kybr.service.verification.VerificationDispatcher;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records submissions instead of verifying, with whether a transaction was still active at the time
 * (a submission inside the transaction would dispatch work a rollback then undoes).
 */
public class RecordingDispatcher implements VerificationDispatcher {

    private final List<Submission> submissions = new CopyOnWriteArrayList<>();

    /** One {@link #submit} call. */
    public record Submission(UUID caseId, boolean inTransaction) {
    }

    @Override
    public void submit(UUID caseId) {
        submissions.add(new Submission(caseId, TransactionSynchronizationManager.isActualTransactionActive()));
    }

    public List<Submission> submissions() {
        return List.copyOf(submissions);
    }

    public List<UUID> submitted() {
        return submissions.stream().map(Submission::caseId).toList();
    }

    public void clear() {
        submissions.clear();
    }
}
