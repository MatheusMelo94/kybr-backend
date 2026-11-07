package com.matheusmelo.kybr.service.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.model.workspace.RuntimeFlags;
import com.matheusmelo.kybr.repository.RuntimeFlagsRepository;
import com.matheusmelo.kybr.support.MovableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RuntimeFlagsServiceTest {

    private final RuntimeFlagsRepository repository = mock(RuntimeFlagsRepository.class);
    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-07T12:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RuntimeFlagsService flags =
            new RuntimeFlagsService(repository, Duration.ofSeconds(10), clock, meters);

    @Test
    void everythingIsOnWhenNoOperatorSetAnything() {
        when(repository.findById(RuntimeFlags.GLOBAL_ID)).thenReturn(Optional.empty());

        assertThat(flags.autofillOn()).isTrue();
        assertThat(flags.providerSpendOn()).isTrue();
        assertThat(meters.get("kybr.provider.spend_off").gauge().value()).isZero();
    }

    @Test
    void aChangeMadeElsewhereIsSeenWithinTheCacheWindow() {
        when(repository.findById(RuntimeFlags.GLOBAL_ID)).thenReturn(Optional.of(RuntimeFlags.defaults()));
        assertThat(flags.autofillOn()).isTrue();

        when(repository.findById(RuntimeFlags.GLOBAL_ID))
                .thenReturn(Optional.of(new RuntimeFlags(RuntimeFlags.GLOBAL_ID, false, false)));
        clock.advance(Duration.ofSeconds(9));
        assertThat(flags.autofillOn()).as("still cached").isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(flags.autofillOn()).isFalse();
        assertThat(flags.providerSpendOn()).isFalse();
        assertThat(meters.get("kybr.provider.spend_off").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void settingASwitchStoresItAndTakesEffectHereAtOnce() {
        when(repository.findById(RuntimeFlags.GLOBAL_ID)).thenReturn(Optional.of(RuntimeFlags.defaults()));
        when(repository.save(any(RuntimeFlags.class))).thenAnswer(call -> call.getArgument(0));
        assertThat(flags.providerSpendOn()).isTrue();

        flags.setProviderSpend(false);

        ArgumentCaptor<RuntimeFlags> saved = ArgumentCaptor.forClass(RuntimeFlags.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(new RuntimeFlags(RuntimeFlags.GLOBAL_ID, true, false));
        assertThat(flags.providerSpendOn()).isFalse();
    }

    @Test
    void insideATransactionTheNewValueTakesEffectHereOnlyAfterCommit() {
        when(repository.findById(RuntimeFlags.GLOBAL_ID)).thenReturn(Optional.of(RuntimeFlags.defaults()));
        when(repository.save(any(RuntimeFlags.class))).thenAnswer(call -> call.getArgument(0));
        assertThat(flags.providerSpendOn()).isTrue();
        TransactionSynchronizationManager.initSynchronization();
        try {
            flags.setProviderSpend(false);
            assertThat(flags.providerSpendOn()).as("before commit").isTrue();

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(flags.providerSpendOn()).as("after commit").isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aRolledBackChangeNeverTakesEffectHere() {
        when(repository.findById(RuntimeFlags.GLOBAL_ID)).thenReturn(Optional.of(RuntimeFlags.defaults()));
        when(repository.save(any(RuntimeFlags.class))).thenAnswer(call -> call.getArgument(0));
        assertThat(flags.autofillOn()).isTrue();
        TransactionSynchronizationManager.initSynchronization();
        try {
            flags.setAutofill(false);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(flags.autofillOn()).isTrue();
    }

    @Test
    void settingAutofillKeepsTheSpendSwitch() {
        when(repository.findById(RuntimeFlags.GLOBAL_ID))
                .thenReturn(Optional.of(new RuntimeFlags(RuntimeFlags.GLOBAL_ID, true, false)));
        when(repository.save(any(RuntimeFlags.class))).thenAnswer(call -> call.getArgument(0));

        flags.setAutofill(false);

        assertThat(flags.autofillOn()).isFalse();
        assertThat(flags.providerSpendOn()).isFalse();
    }
}
