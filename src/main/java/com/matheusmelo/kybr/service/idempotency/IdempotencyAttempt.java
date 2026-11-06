package com.matheusmelo.kybr.service.idempotency;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One execution attempt that holds a record's lease. {@code owner} is the attempt's fencing token
 * (fencing token); every write on the record is conditional on it. The attempt finishes exactly once
 * (complete, completeInTransaction or abandon).
 */
public final class IdempotencyAttempt {

    private final String recordId;
    private final String owner;
    private final AtomicBoolean finished = new AtomicBoolean();

    IdempotencyAttempt(String recordId, String owner) {
        this.recordId = Objects.requireNonNull(recordId, "recordId");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public String recordId() {
        return recordId;
    }

    public String owner() {
        return owner;
    }

    public boolean isFinished() {
        return finished.get();
    }

    /** Marks the attempt finished; false if it already was. */
    boolean finish() {
        return finished.compareAndSet(false, true);
    }

    @Override
    public String toString() {
        return "IdempotencyAttempt[finished=" + finished.get() + "]";
    }
}
