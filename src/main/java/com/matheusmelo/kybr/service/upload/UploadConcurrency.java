package com.matheusmelo.kybr.service.upload;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.exception.RetryLaterException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The per-machine cap on uploads past the cheap checks (parsing, scanning, re-encoding, storing),
 * which hold disk, memory and connections. A request that finds every permit taken is refused at
 * once with 503 {@code INDISPONIVEL} and {@code Retry-After: 5}; it never queues.
 */
@Component
public class UploadConcurrency {

    static final long RETRY_AFTER_SECONDS = 5;

    private final Semaphore permits;
    private final int size;

    @Autowired
    public UploadConcurrency(KybrProperties properties) {
        this(properties.upload().concurrency());
    }

    public UploadConcurrency(int permits) {
        this.size = permits;
        this.permits = new Semaphore(permits);
    }

    /** Takes a permit, or throws 503 when none is free. Close the permit when done. */
    public Permit enter() {
        if (!permits.tryAcquire()) {
            throw new RetryLaterException(RETRY_AFTER_SECONDS);
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        };
    }

    /** Free permits now. */
    public int available() {
        return permits.availablePermits();
    }

    /** The configured number of permits. */
    public int size() {
        return size;
    }

    /** A held permit; closing it more than once releases it once. */
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
