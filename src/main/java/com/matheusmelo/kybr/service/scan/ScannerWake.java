package com.matheusmelo.kybr.service.scan;

import com.matheusmelo.kybr.config.KybrProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Wakes the scanner at the start of a scan session. The scanner machine scales to zero: the first
 * connection starts it, and it answers {@code PONG} only once its signatures are loaded (about 30–60
 * s). So the session probes every {@code kybr.scan.wake-interval} until the scanner is ready or
 * {@code kybr.scan.wake-timeout} (at most 120 s) runs out. An always-on scanner answers the first
 * probe. Each wake-up is timed on {@value #METRIC} by {@code resultado} ({@code ok}, {@code timeout}).
 */
@Component
public class ScannerWake {

    public static final String METRIC = "kybr.scan.wake_seconds";
    private static final Logger log = LoggerFactory.getLogger(ScannerWake.class);

    /** Waits between probes; tests replace it so no real time passes. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final MalwareScanner scanner;
    private final Duration timeout;
    private final Duration interval;
    private final Clock clock;
    private final Sleeper sleeper;
    private final MeterRegistry meters;

    @Autowired
    public ScannerWake(MalwareScanner scanner, KybrProperties properties, Clock clock, MeterRegistry meters) {
        this(scanner, properties.scan().wakeTimeout(), properties.scan().wakeInterval(), clock, Thread::sleep, meters);
    }

    public ScannerWake(MalwareScanner scanner, Duration timeout, Duration interval, Clock clock, Sleeper sleeper,
            MeterRegistry meters) {
        this.scanner = scanner;
        this.timeout = timeout;
        this.interval = interval;
        this.clock = clock;
        this.sleeper = sleeper;
        this.meters = meters;
    }

    /** Probes until the scanner is ready; false when the wake-up time ran out (or the thread was interrupted). */
    public boolean wake() {
        Instant start = clock.instant();
        while (true) {
            if (scanner.ping()) {
                record(start, "ok");
                return true;
            }
            Duration waited = Duration.between(start, clock.instant());
            if (waited.plus(interval).compareTo(timeout) > 0) {
                record(start, "timeout");
                log.warn("event=scanner_wake_timeout seconds={}", waited.toSeconds());
                return false;
            }
            try {
                sleeper.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                record(start, "timeout");
                return false;
            }
        }
    }

    private void record(Instant start, String resultado) {
        Timer.builder(METRIC).tag("resultado", resultado).description("Time to wake the scanner for a scan session")
                .register(meters).record(Duration.between(start, clock.instant()));
    }
}
