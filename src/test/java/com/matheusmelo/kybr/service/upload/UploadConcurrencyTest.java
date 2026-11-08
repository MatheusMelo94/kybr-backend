package com.matheusmelo.kybr.service.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class UploadConcurrencyTest {

    private final UploadConcurrency concurrency = new UploadConcurrency(2);

    private static void assertBusy(UploadConcurrency concurrency) {
        assertThatThrownBy(concurrency::enter).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INDISPONIVEL);
            assertThat(e.headers()).containsEntry("Retry-After", "5");
        });
    }

    @Test
    void aRequestBeyondThePermitsIsRefusedWithRetryAfterFive() {
        UploadConcurrency.Permit first = concurrency.enter();
        UploadConcurrency.Permit second = concurrency.enter();

        assertBusy(concurrency);
        assertThat(concurrency.available()).isZero();

        first.close();
        second.close();
    }

    @Test
    void closingAPermitLetsTheNextRequestIn() {
        UploadConcurrency.Permit first = concurrency.enter();
        try (UploadConcurrency.Permit second = concurrency.enter()) {
            assertBusy(concurrency);
        }

        try (UploadConcurrency.Permit third = concurrency.enter()) {
            assertThat(concurrency.available()).isZero();
        }
        first.close();
        assertThat(concurrency.available()).isEqualTo(2);
    }

    @Test
    void closingAPermitTwiceReleasesItOnce() {
        UploadConcurrency.Permit permit = concurrency.enter();
        permit.close();
        permit.close();

        assertThat(concurrency.available()).isEqualTo(2);
    }
}
