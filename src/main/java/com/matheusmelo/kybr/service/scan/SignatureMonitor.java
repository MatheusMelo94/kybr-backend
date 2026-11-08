package com.matheusmelo.kybr.service.scan;

import com.matheusmelo.kybr.config.KybrProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The live scanner's signature freshness, checked once per scan session: nothing reads it on a
 * schedule, so an idle scale-to-zero scanner is never woken just to be checked. {@link #check()}
 * reads the signature date (after the session's wake-up), publishes the age on {@value #GAUGE} and
 * answers whether it is within {@code kybr.scanner.max-signature-age}; an unreadable date is stale
 * (fail closed). The gauge shows the last session's reading.
 *
 * <p>Without a live scanner (the stand-in, outside prod) there is no signature database and every
 * check passes.
 */
@Component
public class SignatureMonitor {

    public static final String GAUGE = "kybr.scanner.signature_age_hours";
    private static final Logger log = LoggerFactory.getLogger(SignatureMonitor.class);

    /** Reads the scanner's signature date; empty when it can't be read. */
    @FunctionalInterface
    public interface Source {
        Optional<Instant> signatureDate();
    }

    private final Source source;
    private final Duration maxAge;
    private final Clock clock;
    private volatile Instant signatureDate;

    @Autowired
    public SignatureMonitor(ObjectProvider<Source> source, KybrProperties properties, Clock clock,
            MeterRegistry meters) {
        this(source.getIfAvailable(), properties.scanner().maxSignatureAge(), clock, meters);
    }

    /**
     * @param source the live scanner; null without one
     */
    public SignatureMonitor(Source source, Duration maxAge, Clock clock, MeterRegistry meters) {
        this.source = source;
        this.maxAge = maxAge;
        this.clock = clock;
        if (source != null) {
            Gauge.builder(GAUGE, this, SignatureMonitor::ageHours)
                    .description("Age of the malware scanner's signatures at the last scan session")
                    .baseUnit("hours")
                    .register(meters);
        }
    }

    /** A monitor that never refuses: no live scanner, no signature database. */
    public static SignatureMonitor withoutScanner() {
        return new SignatureMonitor(null, Duration.ZERO, Clock.systemUTC(), null);
    }

    /**
     * Reads the signature date now.
     *
     * @return whether the signatures are fresh; false when they are older than the limit or their
     *     date can't be read
     */
    public boolean check() {
        if (source == null) {
            return true;
        }
        Optional<Instant> read;
        try {
            read = source.signatureDate();
        } catch (RuntimeException e) {
            log.warn("event=scanner_signatures_unavailable error={}", e.getClass().getSimpleName());
            read = Optional.empty();
        }
        Instant date = read.orElse(null);
        if (date != null) {
            signatureDate = date;
        }
        if (date == null) {
            log.warn("event=scanner_signatures_unavailable");
            return false;
        }
        if (!fresh(date)) {
            log.error("event=scanner_signatures_stale max_age_hours={}", maxAge.toHours());
            return false;
        }
        return true;
    }

    private boolean fresh(Instant date) {
        return Duration.between(date, clock.instant()).compareTo(maxAge) <= 0;
    }

    private double ageHours() {
        Instant date = signatureDate;
        return date == null ? Double.NaN : Duration.between(date, clock.instant()).toSeconds() / 3600.0;
    }
}
