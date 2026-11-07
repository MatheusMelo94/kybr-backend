package com.matheusmelo.kybr.service.verification;

import com.matheusmelo.kybr.config.KybrProperties;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs verifications on this machine: a fixed pool of {@code kybr.verification.workers} virtual
 * threads and a bounded queue of {@code kybr.verification.queue}. A submission made inside a
 * transaction waits for the commit (a rollback never dispatches). When the queue is full or the
 * machine is shutting down, a submission is dropped without error; the case stays claimable and the
 * {@link RecoveryPoller} picks it up.
 */
@Service
public class BoundedVerificationDispatcher implements VerificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BoundedVerificationDispatcher.class);

    private final VerificationWorker worker;
    private final int workers;
    private final ThreadPoolExecutor executor;
    private volatile boolean stopping;

    @Autowired
    public BoundedVerificationDispatcher(VerificationWorker worker, KybrProperties properties) {
        this(worker, properties.verification().workers(), properties.verification().queue());
    }

    BoundedVerificationDispatcher(VerificationWorker worker, int workers, int queue) {
        this.worker = worker;
        this.workers = workers;
        this.executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue), Thread.ofVirtual().name("verification-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void submit(UUID caseId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(() -> worker.process(caseId));
                }
            });
            return;
        }
        enqueue(() -> worker.process(caseId));
    }

    /** Free worker slots: workers neither busy nor spoken for by queued work. */
    int freeCapacity() {
        return Math.max(0, workers - executor.getActiveCount() - executor.getQueue().size());
    }

    /** Queues one "verify whatever is due"; false when it couldn't be queued. */
    boolean submitNext() {
        return enqueue(worker::processNext);
    }

    boolean isStopping() {
        return stopping;
    }

    /** Stops taking work; queued work that hasn't started is skipped, and its cases stay claimable. */
    @PreDestroy
    public void shutdown() {
        stopping = true;
        executor.shutdown();
    }

    private boolean enqueue(Runnable task) {
        if (stopping) {
            return false;
        }
        try {
            executor.execute(() -> {
                if (!stopping) {
                    task.run();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            log.debug("event=verification_dispatch_dropped");
            return false;
        }
    }
}
