package com.matheusmelo.kybr.service.upload.scan;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.repository.ScanClaims;
import com.matheusmelo.kybr.service.jobs.BackgroundJob;
import com.matheusmelo.kybr.service.jobs.JobName;
import com.matheusmelo.kybr.service.scan.ScannerWake;
import com.matheusmelo.kybr.service.scan.SignatureMonitor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs scan sessions: claims received files and hands them to {@link ScanWorker}s, at most
 * {@code kybr.scan.workers} at once on this machine, until nothing is due. A session wakes the
 * scanner once, when it claims its first file ({@link ScannerWake}), and checks the signatures
 * once after that ({@link SignatureMonitor}); a scanner that doesn't wake, or stale signatures,
 * end the session and put the claimed file back for a later attempt (stale signatures don't count
 * as one). An idle backlog touches the scanner never.
 *
 * <p>A session starts when an upload commits ({@link #nudge()}) and, as the backstop for a lost
 * nudge or an abandoned lease, every {@code kybr.scan.poll-interval} on every machine (this job's
 * {@link #runOnce()}); claims are atomic, so machines never scan the same file. Only one session
 * runs at a time per machine; a nudge during a session makes it look again before it ends. Each
 * run also refreshes the backlog gauges.
 */
@Component
public class ScanDispatcher implements BackgroundJob {

    public static final String BACKLOG_GAUGE = "kybr.scan.backlog";
    public static final String OLDEST_GAUGE = "kybr.scan.backlog_oldest_age_seconds";
    public static final String STUCK_GAUGE = "kybr.scan.stuck";
    private static final Logger log = LoggerFactory.getLogger(ScanDispatcher.class);

    /** A session's readiness. */
    private enum Readiness { READY, NOT_AWAKE, STALE_SIGNATURES }

    /** One session's readiness: the scanner woken and the signatures checked once, by the first worker. */
    private final class Session {
        private Readiness readiness;

        synchronized Readiness readiness() {
            if (readiness == null) {
                readiness = !wake.wake() ? Readiness.NOT_AWAKE
                        : signatures.check() ? Readiness.READY : Readiness.STALE_SIGNATURES;
                if (readiness != Readiness.READY) {
                    log.warn("event=scan_session_not_ready motivo={}", readiness.name().toLowerCase());
                }
            }
            return readiness;
        }
    }

    private final ScanClaims claims;
    private final ScanWorker worker;
    private final ScannerWake wake;
    private final SignatureMonitor signatures;
    private final KybrProperties.Scan settings;
    private final Clock clock;
    private final ExecutorService workers;
    private final ExecutorService nudges;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean again = new AtomicBoolean();
    private final AtomicLong recebidos = new AtomicLong();
    private final AtomicLong verificando = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final AtomicLong stuck = new AtomicLong();

    public ScanDispatcher(ScanClaims claims, ScanWorker worker, ScannerWake wake, SignatureMonitor signatures,
            KybrProperties properties, Clock clock, MeterRegistry meters) {
        this.claims = claims;
        this.worker = worker;
        this.wake = wake;
        this.signatures = signatures;
        this.settings = properties.scan();
        this.clock = clock;
        this.workers = Executors.newFixedThreadPool(settings.workers(), Thread.ofVirtual().name("scan-worker-", 0)
                .factory());
        this.nudges = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("scan-session").factory());
        Gauge.builder(BACKLOG_GAUGE, recebidos, AtomicLong::get).tag("estado", "recebido")
                .description("Received files waiting to be scanned").register(meters);
        Gauge.builder(BACKLOG_GAUGE, verificando, AtomicLong::get).tag("estado", "verificando")
                .description("Files being scanned").register(meters);
        Gauge.builder(OLDEST_GAUGE, oldestAgeSeconds, AtomicLong::get).baseUnit("seconds")
                .description("Age of the oldest file still waiting for its scan").register(meters);
        Gauge.builder(STUCK_GAUGE, stuck, AtomicLong::get)
                .description("Files still waiting for their scan after the stuck threshold").register(meters);
    }

    @Override
    public JobName name() {
        return JobName.SCAN;
    }

    /** One session on the calling thread's watch: returns when nothing is due. */
    @Override
    public void runOnce() {
        drain();
    }

    /** Starts a session in the background after an upload committed (when the job is enabled here). */
    public void nudge() {
        if (settings.enabled()) {
            try {
                nudges.execute(this::drain);
            } catch (RejectedExecutionException e) {
                // shutting down: the next machine's poller scans what was received
            }
        }
    }

    @PreDestroy
    void shutdown() {
        nudges.shutdownNow();
        workers.shutdownNow();
    }

    private void drain() {
        if (!draining.compareAndSet(false, true)) {
            again.set(true);
            return;
        }
        try {
            do {
                again.set(false);
                session();
            } while (again.get());
        } finally {
            draining.set(false);
            refreshGauges();
        }
    }

    private void session() {
        refuseExhausted();
        Session session = new Session();
        AtomicBoolean stop = new AtomicBoolean();
        List<Future<?>> running = new ArrayList<>();
        for (int i = 0; i < settings.workers(); i++) {
            running.add(workers.submit(() -> work(session, stop)));
        }
        for (Future<?> future : running) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop.set(true);
                return;
            } catch (ExecutionException e) {
                log.error("event=scan_worker_error error={}", e.getCause().getClass().getSimpleName());
            }
        }
    }

    /** Refuses every due file whose attempts ran out; no scanner is needed for that. */
    private void refuseExhausted() {
        String owner = UUID.randomUUID().toString();
        Optional<Document> exhausted;
        while ((exhausted = claims.claimExhausted(settings.maxAttempts(), now(), owner, settings.lease())).isPresent()) {
            worker.retryOrRefuse(exhausted.get(), owner);
        }
    }

    private void work(Session session, AtomicBoolean stop) {
        String owner = UUID.randomUUID().toString();
        while (!stop.get()) {
            Optional<Document> claimed = claims.claimNext(settings.maxAttempts(), now(), owner, settings.lease());
            if (claimed.isEmpty()) {
                return;
            }
            Document file = claimed.get();
            Readiness readiness = session.readiness();
            if (readiness != Readiness.READY) {
                stop.set(true);
                if (readiness == Readiness.STALE_SIGNATURES) {
                    worker.rescheduleUncounted(file, owner); // an infrastructure fault, not the file's
                } else {
                    worker.retryOrRefuse(file, owner);
                }
                return;
            }
            try {
                worker.process(file, owner);
            } catch (RuntimeException e) {
                log.error("event=scan_worker_error document_id={} error={}", file.id(), e.getClass().getSimpleName());
                worker.retryOrRefuse(file, owner);
            }
        }
    }

    private void refreshGauges() {
        try {
            Instant now = now();
            ScanClaims.Backlog backlog = claims.backlog(now, settings.stuckAfter());
            recebidos.set(backlog.recebidos());
            verificando.set(backlog.verificando());
            oldestAgeSeconds.set(backlog.oldestPending() == null ? 0
                    : Math.max(0, Duration.between(backlog.oldestPending(), now).toSeconds()));
            stuck.set(backlog.stuck());
            if (backlog.stuck() > 0) {
                log.error("event=scan_backlog_stuck count={}", backlog.stuck());
            }
        } catch (RuntimeException e) {
            log.warn("event=scan_backlog_unavailable error={}", e.getClass().getSimpleName());
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
