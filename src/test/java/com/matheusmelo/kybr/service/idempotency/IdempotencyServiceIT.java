package com.matheusmelo.kybr.service.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import com.matheusmelo.kybr.config.KybrProperties.Hashing;
import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.IdempotencyInProgressException;
import com.matheusmelo.kybr.exception.IdempotencyMismatchException;
import com.matheusmelo.kybr.model.IdempotencyRecord;
import com.matheusmelo.kybr.model.IdempotencyRecord.State;
import com.matheusmelo.kybr.repository.IdempotencyRecordRepository;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService.Begin;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService.Execute;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService.Replay;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.TestSecrets;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.json.JsonMapper;

/** The idempotency state machine at component level, with a movable clock on the replica set. */
@ExtendWith(OutputCaptureExtension.class)
class IdempotencyServiceIT extends MongoIntegrationTest {

    private static final String BUSINESS = "idempotency_service_it_business";
    private static final String HASH = "request-hash-1";
    private static final String JSON = "application/json";
    private static final byte[] BODY = "{\"case_token\":\"synthetic-marker-7731\"}".getBytes(StandardCharsets.UTF_8);

    record Created(String id) {
    }

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoTransactionManager transactionManager;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ReplayBodyCipher cipher;

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-11T12:00:00Z"));
    private final KeyHasher hasher = new KeyHasher(new Hashing(TestSecrets.HASH_SECRET, null));
    private IdempotencyService service;
    private String key;
    private String slug;

    @BeforeEach
    void setUp() {
        service = service(hasher, cipher);
        key = UUID.randomUUID().toString();
        slug = "slug-" + UUID.randomUUID();
    }

    @Test
    void theFirstAttemptExecutesUnderAnHmacIdThatHoldsNoRawKey() {
        Execute execute = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));

        String expectedId = hasher.hmac("PUBLIC_VERIFY|" + slug + "|" + key);
        assertThat(execute.attempt().recordId()).isEqualTo(expectedId).doesNotContain(key);
        IdempotencyRecord stored = repository.findById(expectedId).orElseThrow();
        assertThat(stored.state()).isEqualTo(State.IN_PROGRESS);
        assertThat(stored.leaseOwner()).isEqualTo(execute.attempt().owner());
        assertThat(stored.leaseUntil()).isEqualTo(clock.instant().plusSeconds(60));
        assertThat(mongoTemplate.getCollection(IdempotencyRecord.COLLECTION)
                .find(new Document("_id", expectedId)).first().toJson()).doesNotContain(key).doesNotContain(slug);
    }

    @Test
    void aCompletedRecordReplaysTheExactStatusContentTypeAndBytes() {
        Execute execute = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        service.complete(execute.attempt(), 201, JSON, BODY);

        Replay replay = replays(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));

        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.contentType()).isEqualTo(JSON);
        assertThat(replay.body()).isEqualTo(BODY);
    }

    @Test
    void theSameKeyWithAnotherRequestIsAMismatch() {
        Execute execute = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        service.complete(execute.attempt(), 201, JSON, BODY);

        assertThatThrownBy(() -> service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, "another-hash"))
                .isInstanceOf(IdempotencyMismatchException.class);
        assertThat(replays(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH)).body()).isEqualTo(BODY);
    }

    @Test
    void aSecondAttemptWhileTheFirstHoldsTheLeaseIsInProgress() {
        executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        clock.advance(Duration.ofSeconds(59));

        assertThatThrownBy(() -> service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH))
                .isInstanceOfSatisfying(IdempotencyInProgressException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.headers()).containsEntry("Retry-After", "1");
                });
    }

    @Test
    void anExpiredLeaseIsTakenOverByANewOwner() {
        Execute first = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        clock.advance(Duration.ofSeconds(61));

        Execute second = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));

        assertThat(second.attempt().recordId()).isEqualTo(first.attempt().recordId());
        assertThat(second.attempt().owner()).isNotEqualTo(first.attempt().owner());
        assertThat(repository.findById(second.attempt().recordId())).get()
                .extracting(IdempotencyRecord::leaseOwner).isEqualTo(second.attempt().owner());
    }

    @ParameterizedTest(name = "status {0} is not stored")
    @ValueSource(ints = {500, 503, 429, 401, 408})
    void aNonStorableOutcomeDeletesTheRecordSoARetryExecutes(int status) {
        Execute first = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));

        service.complete(first.attempt(), status, JSON, BODY);

        assertThat(repository.findById(first.attempt().recordId())).isEmpty();
        executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
    }

    @ParameterizedTest(name = "status {0} is stored")
    @ValueSource(ints = {200, 201, 404, 409, 422})
    void aStorableOutcomeIsReplayed(int status) {
        Execute first = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));

        service.complete(first.attempt(), status, JSON, BODY);

        assertThat(replays(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH)).status()).isEqualTo(status);
    }

    @Test
    void anAbandonedAttemptDeletesItsRecord() {
        Execute first = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));

        service.abandon(first.attempt());

        assertThat(repository.findById(first.attempt().recordId())).isEmpty();
    }

    @Test
    void eachProfileSetsItsTtl() {
        Execute verify = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        Execute panel = executes(service.begin(IdempotencyProfile.PANEL, "signup", key, HASH));

        assertThat(repository.findById(verify.attempt().recordId())).get()
                .extracting(IdempotencyRecord::expiresAt).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        assertThat(repository.findById(panel.attempt().recordId())).get()
                .extracting(IdempotencyRecord::expiresAt).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
    }

    @Test
    void completeInTransactionCommitsTheBusinessWriteAndTheRecordTogether() {
        Execute execute = executes(service.begin(IdempotencyProfile.PANEL, "signup", key, HASH));
        String businessId = UUID.randomUUID().toString();

        Created result = service.completeInTransaction(execute.attempt(), 201, () -> {
            mongoTemplate.insert(new Document("_id", businessId), BUSINESS);
            return new Created(businessId);
        });

        assertThat(result).isEqualTo(new Created(businessId));
        assertThat(businessDocs(businessId)).isEqualTo(1);
        Replay replay = replays(service.begin(IdempotencyProfile.PANEL, "signup", key, HASH));
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.contentType()).isEqualTo(JSON);
        assertThat(jsonMapper.readValue(replay.body(), Created.class)).isEqualTo(result);
    }

    @Test
    void completeInTransactionRollsBackTheBusinessWriteAndTheRecordWhenTheCallbackThrows() {
        Execute execute = executes(service.begin(IdempotencyProfile.PANEL, "signup", key, HASH));
        String businessId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> service.completeInTransaction(execute.attempt(), 201, () -> {
            mongoTemplate.insert(new Document("_id", businessId), BUSINESS);
            throw new IllegalStateException("synthetic failure after the business write");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(businessDocs(businessId)).isZero();
        assertThat(repository.findById(execute.attempt().recordId())).isEmpty();
        executes(service.begin(IdempotencyProfile.PANEL, "signup", key, HASH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000-0000-0000-0000-000000000000", "ffffffff-ffff-ffff-ffff-ffffffffffff",
        "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF", "", " ", "not-a-uuid", "{3f2b8c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e}",
        "3f2b8c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e0", "3f2b8c1e4d5a4b6c8d7e9f0a1b2c3d4e", " 3f2b8c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e",
        "3f2b8c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4"})
    void anInvalidKeyIsRejectedOnTheIdempotencyKeyField(String rawKey) {
        assertThatThrownBy(() -> service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, rawKey, HASH))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALOR_INVALIDO);
                    assertThat(e.field()).isEqualTo("Idempotency-Key");
                });
    }

    @Test
    void aMissingKeyIsRejected() {
        assertThatThrownBy(() -> service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, null, HASH))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.field()).isEqualTo("Idempotency-Key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0190c3a0-8b1e-11ef-9a6b-0242ac120002", "0190c3a0-8b1e-7c2d-9a6b-0242ac120002"})
    void anyUuidVersionIsAccepted(String v1OrV7) {
        executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, v1OrV7, HASH));
    }

    @Test
    void publicVerifyReplaysUpToTenMinutesAndExecutesAfreshAfterEvenIfTheRecordIsStillThere() {
        Execute first = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        service.complete(first.attempt(), 201, JSON, BODY);
        Instant start = clock.instant();

        clock.set(start.plus(Duration.ofMinutes(9)).plusSeconds(59));
        replays(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));

        clock.set(start.plus(Duration.ofMinutes(10)).plusSeconds(1));
        assertThat(repository.findById(first.attempt().recordId())).as("TTL monitor has not run").isPresent();
        executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
    }

    @Test
    void panelReplaysUpTo24HoursAndExecutesAfreshAfter() {
        Execute first = executes(service.begin(IdempotencyProfile.PANEL, "signup", key, HASH));
        service.complete(first.attempt(), 201, JSON, BODY);
        Instant start = clock.instant();

        clock.set(start.plus(Duration.ofHours(23)).plusSeconds(59 * 60));
        replays(service.begin(IdempotencyProfile.PANEL, "signup", key, HASH));

        clock.set(start.plus(Duration.ofHours(24)).plusSeconds(60));
        assertThat(repository.findById(first.attempt().recordId())).isPresent();
        executes(service.begin(IdempotencyProfile.PANEL, "signup", key, HASH));
    }

    @Test
    void anUppercaseRetryOfTheSameKeyReplays() {
        Execute first = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        service.complete(first.attempt(), 201, JSON, BODY);

        assertThat(replays(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key.toUpperCase(Locale.ROOT), HASH))
                .body()).isEqualTo(BODY);
    }

    @Test
    void aTakeoverRaceRollsBackTheLosersBusinessWrite() {
        Execute a = executes(service.begin(IdempotencyProfile.PANEL, "signup", key, HASH));
        clock.advance(Duration.ofSeconds(61));
        Execute b = executes(service.begin(IdempotencyProfile.PANEL, "signup", key, HASH));
        String raceId = UUID.randomUUID().toString();

        service.completeInTransaction(b.attempt(), 201, () -> {
            mongoTemplate.insert(new Document("race", raceId).append("by", "B"), BUSINESS);
            return new Created("B");
        });
        assertThatThrownBy(() -> service.completeInTransaction(a.attempt(), 201, () -> {
            mongoTemplate.insert(new Document("race", raceId).append("by", "A"), BUSINESS);
            return new Created("A");
        })).isInstanceOfSatisfying(IdempotencyLeaseLostException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INDISPONIVEL);
            assertThat(e.headers()).containsEntry("Retry-After", "1");
        });

        assertThat(mongoTemplate.find(Query.query(Criteria.where("race").is(raceId)), Document.class, BUSINESS))
                .singleElement().satisfies(doc -> assertThat(doc.getString("by")).isEqualTo("B"));
        Replay replay = replays(service.begin(IdempotencyProfile.PANEL, "signup", key, HASH));
        assertThat(jsonMapper.readValue(replay.body(), Created.class)).isEqualTo(new Created("B"));
    }

    @Test
    void aFencedCompleteAfterLosingTheLeaseThrowsLeaseLost(CapturedOutput output) {
        Execute a = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        clock.advance(Duration.ofSeconds(61));
        executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));

        assertThatThrownBy(() -> service.complete(a.attempt(), 201, JSON, BODY))
                .isInstanceOf(IdempotencyLeaseLostException.class);
        assertThat(output).contains("event=idempotency_lease_lost");
    }

    @Test
    void theStoredBodyIsCiphertextBoundToItsRecord() {
        Execute execute = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        service.complete(execute.attempt(), 201, JSON, BODY);

        byte[] stored = storedBody(execute.attempt().recordId());
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("synthetic-marker-7731");
        assertThat(cipher.decrypt(stored, execute.attempt().recordId().getBytes(StandardCharsets.UTF_8))).isEqualTo(BODY);
    }

    @Test
    void aBodyMovedFromAnotherRecordFailsWithoutReExecuting(CapturedOutput output) {
        Execute donor = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        service.complete(donor.attempt(), 201, JSON, BODY);
        String otherKey = UUID.randomUUID().toString();
        Execute target = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, otherKey, HASH));
        service.complete(target.attempt(), 201, JSON, "{}".getBytes(StandardCharsets.UTF_8));
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(target.attempt().recordId())),
                new Update().set("body", storedBody(donor.attempt().recordId())), IdempotencyRecord.class);

        assertThatThrownBy(() -> service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, otherKey, HASH))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.ERRO_INTERNO);
                    assertThat(e.status().value()).isEqualTo(500);
                });
        assertThat(output).contains("event=idempotency_decrypt_failed").doesNotContain("synthetic-marker-7731");
        assertThat(repository.findById(target.attempt().recordId())).get()
                .extracting(IdempotencyRecord::state).isEqualTo(State.COMPLETED);
    }

    @Test
    void aBodyUnderAnUnknownKeyFailsWithoutReExecuting() throws Exception {
        Execute execute = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        service.complete(execute.attempt(), 201, JSON, BODY);
        IdempotencyService otherKeyset = service(hasher, new ReplayBodyCipher(freshKeyset()));

        assertThatThrownBy(() -> otherKeyset.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ERRO_INTERNO));
    }

    @Test
    void aRecordWrittenUnderThePreviousHashSecretIsStillFoundAfterRotation() {
        Execute execute = executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        service.complete(execute.attempt(), 201, JSON, BODY);
        IdempotencyService rotated =
                service(new KeyHasher(new Hashing(TestSecrets.HASH_SECRET_PREVIOUS, TestSecrets.HASH_SECRET)), cipher);

        assertThat(replays(rotated.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH)).body()).isEqualTo(BODY);
        assertThatThrownBy(() -> rotated.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, "another-hash"))
                .isInstanceOf(IdempotencyMismatchException.class);
    }

    @Test
    void anInProgressRecordUnderThePreviousSecretIsHonouredButNeverTakenOver() {
        executes(service.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        IdempotencyService rotated =
                service(new KeyHasher(new Hashing(TestSecrets.HASH_SECRET_PREVIOUS, TestSecrets.HASH_SECRET)), cipher);

        assertThatThrownBy(() -> rotated.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH))
                .isInstanceOf(IdempotencyInProgressException.class);

        clock.advance(Duration.ofSeconds(60));
        Execute fresh = executes(rotated.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));
        assertThat(fresh.attempt().recordId()).isNotEqualTo(hasher.hmac("PUBLIC_VERIFY|" + slug + "|" + key));
    }

    @Test
    void aNewRecordAfterRotationUsesTheCurrentSecret() {
        IdempotencyService rotated =
                service(new KeyHasher(new Hashing(TestSecrets.HASH_SECRET_PREVIOUS, TestSecrets.HASH_SECRET)), cipher);

        Execute execute = executes(rotated.begin(IdempotencyProfile.PUBLIC_VERIFY, slug, key, HASH));

        assertThat(execute.attempt().recordId()).isEqualTo(
                new KeyHasher(new Hashing(TestSecrets.HASH_SECRET_PREVIOUS, null)).hmac("PUBLIC_VERIFY|" + slug + "|" + key));
    }

    private IdempotencyService service(KeyHasher keyHasher, ReplayBodyCipher replayCipher) {
        return new IdempotencyService(repository, keyHasher, replayCipher, clock, transactionManager, jsonMapper);
    }

    private long businessDocs(String id) {
        return mongoTemplate.count(Query.query(Criteria.where("_id").is(id)), BUSINESS);
    }

    private byte[] storedBody(String recordId) {
        Document doc = mongoTemplate.getCollection(IdempotencyRecord.COLLECTION).find(new Document("_id", recordId)).first();
        return doc.get("body", Binary.class).getData();
    }

    private static Execute executes(Begin begin) {
        assertThat(begin).isInstanceOf(Execute.class);
        return (Execute) begin;
    }

    private static Replay replays(Begin begin) {
        assertThat(begin).isInstanceOf(Replay.class);
        return (Replay) begin;
    }

    private static String freshKeyset() throws Exception {
        AeadConfig.register();
        KeysetHandle handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM);
        String json = TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get());
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A clock the test moves explicitly. */
    static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
