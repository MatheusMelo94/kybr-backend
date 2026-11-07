package com.matheusmelo.kybr.service.company;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.workspace.RuntimeFlags;
import com.matheusmelo.kybr.repository.RuntimeFlagsRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The process-wide kill switches: the global autofill switch and the provider-spend brake. Each
 * machine serves them from memory for {@code kybr.runtime-flags.cache-ttl} (10 s), so an operator's
 * change takes effect everywhere within that window, and at once on the machine that made it (once
 * its transaction commits, when it was made in one).
 * With no stored document, everything is on.
 *
 * <p>The {@code kybr.link.autofill_off} and {@code kybr.provider.spend_off} gauges read 1 while the
 * switch is off, from the last value this machine read.
 */
@Service
public class RuntimeFlagsService implements ProviderSpendSwitch {

    private final RuntimeFlagsRepository repository;
    private final Duration cacheTtl;
    private final Clock clock;
    private volatile Snapshot snapshot;

    private record Snapshot(RuntimeFlags flags, Instant readAt) {
    }

    @Autowired
    public RuntimeFlagsService(RuntimeFlagsRepository repository, KybrProperties properties, Clock clock,
            MeterRegistry meters) {
        this(repository, properties.runtimeFlags().cacheTtl(), clock, meters);
    }

    RuntimeFlagsService(RuntimeFlagsRepository repository, Duration cacheTtl, Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
        Gauge.builder("kybr.link.autofill_off", this, flags -> flags.offGauge(RuntimeFlags::autofillAtivo))
                .register(meters);
        Gauge.builder("kybr.provider.spend_off", this, flags -> flags.offGauge(RuntimeFlags::gastoProvedorAtivo))
                .register(meters);
    }

    /** False when an operator switched company lookups off everywhere. */
    public boolean autofillOn() {
        return flags().autofillAtivo();
    }

    @Override
    public boolean providerSpendOn() {
        return flags().gastoProvedorAtivo();
    }

    /** Stores the global autofill switch; the other switch keeps its stored value. */
    public synchronized void setAutofill(boolean on) {
        RuntimeFlags current = read();
        rememberOnCommit(repository.save(new RuntimeFlags(RuntimeFlags.GLOBAL_ID, on, current.gastoProvedorAtivo())));
    }

    /** Stores the provider-spend switch; the other switch keeps its stored value. */
    public synchronized void setProviderSpend(boolean on) {
        RuntimeFlags current = read();
        rememberOnCommit(repository.save(new RuntimeFlags(RuntimeFlags.GLOBAL_ID, current.autofillAtivo(), on)));
    }

    private RuntimeFlags flags() {
        Snapshot current = snapshot;
        Instant now = clock.instant();
        if (current == null || !now.isBefore(current.readAt().plus(cacheTtl))) {
            return remember(read());
        }
        return current.flags();
    }

    private RuntimeFlags read() {
        return repository.findById(RuntimeFlags.GLOBAL_ID).orElseGet(RuntimeFlags::defaults);
    }

    /**
     * Serves a stored value here at once, or, inside a transaction (with its audit entry), only once
     * it commits: a rolled-back change is never served.
     */
    private void rememberOnCommit(RuntimeFlags flags) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            remember(flags);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                remember(flags);
            }
        });
    }

    private RuntimeFlags remember(RuntimeFlags flags) {
        snapshot = new Snapshot(flags, clock.instant());
        return flags;
    }

    /** 1 when {@code switchOn} is false in the last value read, so a scrape never reads the database. */
    private double offGauge(Predicate<RuntimeFlags> switchOn) {
        Snapshot current = snapshot;
        return current != null && !switchOn.test(current.flags()) ? 1.0 : 0.0;
    }
}
