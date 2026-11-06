package com.matheusmelo.kybr.service.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.config.KybrProperties.Hashing;
import com.matheusmelo.kybr.exception.ServiceUnavailableException;
import com.matheusmelo.kybr.repository.IdempotencyRecordRepository;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.support.TestSecrets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.MongoTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Branches that need no database: fail-closed and the storable-outcome rule. */
class IdempotencyServiceTest {

    @Test
    void aMongoFailureFailsClosedAsServiceUnavailable() {
        IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
        when(repository.deleteIfExpired(anyString(), any())).thenThrow(new DataAccessResourceFailureException("down"));
        when(repository.findById(anyString())).thenThrow(new DataAccessResourceFailureException("down"));
        when(repository.insertIfAbsent(any())).thenThrow(new DataAccessResourceFailureException("down"));
        IdempotencyService service = new IdempotencyService(repository,
                new KeyHasher(new Hashing(TestSecrets.HASH_SECRET, null)),
                new ReplayBodyCipher(TestSecrets.IDEMPOTENCY_ENC_KEY),
                Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC),
                mock(MongoTransactionManager.class), JsonMapper.builder().build());

        assertThatThrownBy(() -> service.begin(IdempotencyProfile.PUBLIC_VERIFY, "slug-a",
                UUID.randomUUID().toString(), "hash")).isInstanceOf(ServiceUnavailableException.class);
    }

    @ParameterizedTest(name = "{0} storable={1}")
    @CsvSource({"200, true", "201, true", "204, true", "400, true", "403, true", "404, true", "409, true",
        "422, true", "401, false", "408, false", "429, false", "500, false", "503, false", "302, false", "100, false"})
    void onlySuccessesAndDeterministicClientErrorsAreStored(int status, boolean storable) {
        assertThat(IdempotencyService.isStorable(status)).isEqualTo(storable);
    }
}
