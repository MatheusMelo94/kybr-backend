package com.matheusmelo.kybr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;

class TransactionRetryTest {

    private final List<Duration> sleeps = new ArrayList<>();
    private final TransactionRetry retry = new TransactionRetry(3, Duration.ofMillis(50), sleeps::add);

    @Test
    void retriesATransientTransactionErrorThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new UncategorizedMongoDbException("write conflict", transientError());
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasSize(2).allSatisfy(sleep -> assertThat(sleep).isPositive()
                .isLessThanOrEqualTo(Duration.ofMillis(100)));
    }

    @Test
    void givesUpAfterTheLastAttemptWithTheOriginalException() {
        AtomicInteger calls = new AtomicInteger();
        UncategorizedMongoDbException conflict = new UncategorizedMongoDbException("write conflict", transientError());

        assertThatThrownBy(() -> retry.execute(() -> {
            calls.incrementAndGet();
            throw conflict;
        })).isSameAs(conflict);
        assertThat(calls).hasValue(3);
    }

    @Test
    void neverRetriesOtherFailures() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute(() -> {
            calls.incrementAndGet();
            throw new DataIntegrityViolationException("duplicate key");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void findsTheLabelAnywhereInTheCauseChain() {
        assertThat(TransactionRetry.isTransient(new IllegalStateException("outer",
                new UncategorizedMongoDbException("x", transientError())))).isTrue();
        assertThat(TransactionRetry.isTransient(transientError())).isTrue();
        assertThat(TransactionRetry.isTransient(new MongoException(112, "write conflict"))).isFalse();
        assertThat(TransactionRetry.isTransient(new IllegalStateException())).isFalse();
    }

    private static MongoException transientError() {
        MongoException error = new MongoException(112, "write conflict");
        error.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        return error;
    }
}
