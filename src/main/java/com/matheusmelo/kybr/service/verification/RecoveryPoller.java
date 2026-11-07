package com.matheusmelo.kybr.service.verification;

import com.matheusmelo.kybr.config.KybrProperties;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Every {@code kybr.verification.poll-interval} (5 s), on every machine, queues one "verify whatever
 * is due" per free worker slot: new cases whose dispatch was dropped, cases due for a retry, and
 * cases whose worker died (expired lease). Claims are atomic, so machines never verify a case twice
 * at once. Stops claiming at shutdown. Off when {@code kybr.verification.poller-enabled} is false
 * (tests only; prod refuses to start that way).
 */
@Component
@ConditionalOnProperty(prefix = "kybr.verification", name = "poller-enabled", havingValue = "true",
        matchIfMissing = true)
public class RecoveryPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RecoveryPoller.class);

    private final BoundedVerificationDispatcher dispatcher;
    private final Duration interval;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    @Autowired
    public RecoveryPoller(BoundedVerificationDispatcher dispatcher, KybrProperties properties) {
        this(dispatcher, properties.verification().pollInterval());
    }

    RecoveryPoller(BoundedVerificationDispatcher dispatcher, Duration interval) {
        this.dispatcher = dispatcher;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("recovery-poller").factory());
        scheduler.scheduleWithFixedDelay(this::poll, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void poll() {
        try {
            for (int free = dispatcher.freeCapacity(); free > 0 && running && !dispatcher.isStopping(); free--) {
                if (!dispatcher.submitNext()) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.error("event=recovery_poll_error error={}", e.getClass().getSimpleName());
        }
    }
}
