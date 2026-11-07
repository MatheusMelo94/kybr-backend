package com.matheusmelo.kybr.service;

import com.mongodb.MongoException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Re-runs a whole MongoDB transaction when it fails with an error labelled
 * {@code TransientTransactionError} (e.g. a write conflict with a concurrent transaction), as the
 * driver guidance prescribes: at most {@code maxAttempts} runs, with a short jittered pause between
 * them. Any other failure, and the last transient one, propagates unchanged. The callback must be
 * a complete transaction (begin to commit), safe to run again after an abort.
 */
public class TransactionRetry {

    /**
     * The default: ten runs, pauses up to 25 ms times the attempt number (at most about 1.1 s of
     * pauses in all). A document several requests write at once can make one of them lose a few
     * conflicts in a row; ten runs outlast that without holding a request for long.
     */
    public static final TransactionRetry DEFAULT = new TransactionRetry(10, Duration.ofMillis(25), TransactionRetry::sleep);

    private final int maxAttempts;
    private final Duration maxBackoff;
    private final Consumer<Duration> sleeper;

    public TransactionRetry(int maxAttempts, Duration maxBackoff, Consumer<Duration> sleeper) {
        if (maxAttempts < 1 || maxBackoff.isNegative() || maxBackoff.isZero()) {
            throw new IllegalArgumentException("maxAttempts >= 1 and a positive maxBackoff are required");
        }
        this.maxAttempts = maxAttempts;
        this.maxBackoff = maxBackoff;
        this.sleeper = sleeper;
    }

    public <T> T execute(Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (RuntimeException e) {
                if (attempt >= maxAttempts || !isTransient(e)) {
                    throw e;
                }
                sleeper.accept(backoff(attempt));
            }
        }
    }

    /** True when the error, or any cause, is a MongoDB error labelled transient for transactions. */
    public static boolean isTransient(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof MongoException mongo
                    && mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }
        }
        return false;
    }

    /** Uniform in (0, maxBackoff × attempt]. */
    private Duration backoff(int attempt) {
        long bound = maxBackoff.toNanos() * attempt;
        return Duration.ofNanos(1 + ThreadLocalRandom.current().nextLong(bound));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry a transaction", e);
        }
    }
}
