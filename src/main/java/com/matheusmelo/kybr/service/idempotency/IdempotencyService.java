package com.matheusmelo.kybr.service.idempotency;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.IdempotencyInProgressException;
import com.matheusmelo.kybr.exception.IdempotencyMismatchException;
import com.matheusmelo.kybr.exception.ServiceUnavailableException;
import com.matheusmelo.kybr.model.IdempotencyRecord;
import com.matheusmelo.kybr.model.IdempotencyRecord.State;
import com.matheusmelo.kybr.repository.IdempotencyRecordRepository;
import com.matheusmelo.kybr.service.KeyHasher;
import com.matheusmelo.kybr.service.TransactionRetry;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The idempotency state machine. Record {@code _id = hmac(profile|binding|key)}.
 *
 * <pre>
 * begin:  expired record (expiresAt ≤ now, app clock)      → deleted, treated as absent
 *         record under the previous hash secret            → replay / mismatch / in progress only
 *         insert IN_PROGRESS with a fresh fencing owner    → Execute
 *         duplicate → requestHash differs                  → 422 IdempotencyMismatchException
 *                   → COMPLETED                            → Replay (decrypted; failure → 500, never re-executes)
 *                   → IN_PROGRESS, lease held              → 503 IdempotencyInProgressException
 *                   → IN_PROGRESS, lease expired           → fenced takeover → Execute
 * finish: storable outcome → fenced complete (body AEAD-encrypted, AAD = _id); otherwise fenced delete.
 *         A fenced write matching nothing → IdempotencyLeaseLostException (503).
 * </pre>
 *
 * <p>Every {@code @Idempotent} endpoint either uses {@link #completeInTransaction} (business write and
 * completion in one MongoDB transaction) or stores the key on its primary document under a unique
 * index and uses {@link #complete} (the B1+ rule). Fails closed: MongoDB unreachable →
 * 503.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    public static final String KEY_HEADER = "Idempotency-Key";
    static final Duration LEASE = Duration.ofSeconds(60);

    private static final Pattern UUID_FORM =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final UUID NIL = new UUID(0, 0);
    private static final UUID MAX = new UUID(-1, -1);
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private final IdempotencyRecordRepository repository;
    private final KeyHasher keyHasher;
    private final ReplayBodyCipher cipher;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final JsonMapper jsonMapper;
    private final SecureRandom random = new SecureRandom();

    /** The outcome of {@link #begin}: run the handler, or replay a stored result. */
    public sealed interface Begin permits Execute, Replay {
    }

    public record Execute(IdempotencyAttempt attempt) implements Begin {
    }

    /** A stored result: the exact status, content type and plaintext body bytes. */
    public record Replay(int status, String contentType, byte[] body) implements Begin {
    }

    public IdempotencyService(IdempotencyRecordRepository repository, KeyHasher keyHasher, ReplayBodyCipher cipher,
            Clock clock, MongoTransactionManager transactionManager, JsonMapper jsonMapper) {
        this.repository = repository;
        this.keyHasher = keyHasher;
        this.cipher = cipher;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jsonMapper = jsonMapper;
    }

    /**
     * Starts an attempt for {@code rawKey} (the {@value #KEY_HEADER} value) under {@code profile}
     * and {@code binding}.
     *
     * @throws ApiException 422 {@code VALOR_INVALIDO} on {@value #KEY_HEADER} for a missing,
     *     malformed, nil or max UUID; {@link IdempotencyMismatchException};
     *     {@link IdempotencyInProgressException}; {@link IdempotencyReplayFailedException};
     *     {@link ServiceUnavailableException}
     */
    public Begin begin(IdempotencyProfile profile, String binding, String rawKey, String requestHash) {
        String key = normalizeKey(rawKey);
        if (binding == null || binding.isBlank()) {
            throw new IllegalArgumentException("binding is required");
        }
        List<String> ids = keyHasher.hmacCandidates(profile.name() + '|' + binding + '|' + key);
        try {
            return begin(profile, ids, requestHash, clock.instant());
        } catch (DataAccessException e) {
            throw new ServiceUnavailableException(e);
        }
    }

    /**
     * Fenced, non-transactional completion: a storable {@code status} is stored for replay, any
     * other outcome deletes the record so a retry re-executes.
     *
     * @throws IdempotencyLeaseLostException when another attempt has taken the lease over
     */
    public void complete(IdempotencyAttempt attempt, int status, String contentType, byte[] body) {
        finish(attempt);
        boolean matched;
        try {
            matched = isStorable(status)
                    ? repository.complete(attempt.recordId(), attempt.owner(), status, contentType, encrypt(attempt, body))
                    : repository.delete(attempt.recordId(), attempt.owner());
        } catch (DataAccessException e) {
            throw new ServiceUnavailableException(e);
        }
        if (!matched && isStorable(status)) {
            throw leaseLost();
        }
    }

    /**
     * Runs {@code businessWrite} and the fenced completion in one MongoDB transaction, storing the
     * result serialised as JSON with {@code status}. If the callback throws, both roll back and the
     * record is deleted (a retry re-executes). If the lease was lost, the business write rolls back
     * and {@link IdempotencyLeaseLostException} is thrown. A transient transaction error (a write
     * conflict) re-runs the whole transaction, callback included ({@link TransactionRetry}).
     */
    public <T> T completeInTransaction(IdempotencyAttempt attempt, int status, Supplier<T> businessWrite) {
        if (!isStorable(status)) {
            throw new IllegalArgumentException("status " + status + " is not storable");
        }
        finish(attempt);
        try {
            return TransactionRetry.DEFAULT.execute(() -> transactions.execute(tx -> {
                T result = businessWrite.get();
                byte[] body = encrypt(attempt, jsonMapper.writeValueAsBytes(result));
                if (!repository.complete(attempt.recordId(), attempt.owner(), status, JSON, body)) {
                    throw leaseLost();
                }
                return result;
            }));
        } catch (IdempotencyLeaseLostException e) {
            throw e;
        } catch (RuntimeException e) {
            deleteQuietly(attempt);
            throw e;
        }
    }

    /** Ends an attempt whose handler failed: fenced delete, so a retry re-executes. */
    public void abandon(IdempotencyAttempt attempt) {
        finish(attempt);
        deleteQuietly(attempt);
    }

    /** 2xx, or a 4xx other than 401, 408 and 429; 1xx, 3xx and 5xx are never stored. */
    static boolean isStorable(int status) {
        if (status >= 200 && status < 300) {
            return true;
        }
        return status >= 400 && status < 500 && status != 401 && status != 408 && status != 429;
    }

    private Begin begin(IdempotencyProfile profile, List<String> ids, String requestHash, Instant now) {
        String currentId = ids.getFirst();
        repository.deleteIfExpired(currentId, now);
        // Old-secret record: replay, mismatch and in-progress checks only; a previous-secret record with an
        // expired lease is ignored (never taken over), and new records always use the current id.
        for (String previousId : ids.subList(1, ids.size())) {
            Optional<IdempotencyRecord> previous = repository.findById(previousId).filter(r -> isLive(r, now));
            Begin begin = previous.isPresent() ? existing(previous.get(), requestHash, now) : null;
            if (begin != null) {
                return begin;
            }
        }
        String owner = newOwner();
        if (repository.insertIfAbsent(IdempotencyRecord.inProgress(currentId, profile.name(), requestHash, owner, now,
                now.plus(LEASE), now.plus(profile.ttl())))) {
            return new Execute(new IdempotencyAttempt(currentId, owner));
        }
        IdempotencyRecord existing = repository.findById(currentId).orElseThrow(IdempotencyInProgressException::new);
        Begin begin = existing(existing, requestHash, now);
        if (begin != null) {
            return begin;
        }
        if (repository.takeOverIfLeaseExpired(currentId, owner, now, now.plus(LEASE))) {
            return new Execute(new IdempotencyAttempt(currentId, owner));
        }
        throw new IdempotencyInProgressException();
    }

    /** The answer for an existing live record, or null when its lease has expired (takeover). */
    private Begin existing(IdempotencyRecord record, String requestHash, Instant now) {
        if (!record.requestHash().equals(requestHash)) {
            throw new IdempotencyMismatchException();
        }
        if (record.state() == State.COMPLETED) {
            return replay(record);
        }
        if (record.leaseUntil().isAfter(now)) {
            throw new IdempotencyInProgressException();
        }
        return null;
    }

    private Replay replay(IdempotencyRecord record) {
        try {
            return new Replay(record.status(), record.contentType(), cipher.decrypt(record.body(), aad(record.id())));
        } catch (ReplayBodyDecryptException e) {
            log.error("event=idempotency_decrypt_failed profile={}", record.profile());
            throw new IdempotencyReplayFailedException(e);
        }
    }

    private byte[] encrypt(IdempotencyAttempt attempt, byte[] body) {
        return cipher.encrypt(body == null ? new byte[0] : body, aad(attempt.recordId()));
    }

    private void deleteQuietly(IdempotencyAttempt attempt) {
        try {
            repository.delete(attempt.recordId(), attempt.owner());
        } catch (DataAccessException e) {
            // the lease expires and the TTL removes the record; a retry then re-executes
            log.warn("event=idempotency_abandon_failed error={}", e.getClass().getSimpleName());
        }
    }

    private static void finish(IdempotencyAttempt attempt) {
        if (!attempt.finish()) {
            throw new IllegalStateException("idempotency attempt already finished");
        }
    }

    private static IdempotencyLeaseLostException leaseLost() {
        log.warn("event=idempotency_lease_lost");
        return new IdempotencyLeaseLostException();
    }

    private static boolean isLive(IdempotencyRecord record, Instant now) {
        return record.expiresAt().isAfter(now);
    }

    private static byte[] aad(String recordId) {
        return recordId.getBytes(StandardCharsets.UTF_8);
    }

    private String newOwner() {
        byte[] token = new byte[16];
        random.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /**
     * A UUID of any version in the 36-character hyphenated form, normalised to lower case;
     * the nil and max UUIDs are rejected (they are never random).
     */
    private static String normalizeKey(String rawKey) {
        if (rawKey == null || !UUID_FORM.matcher(rawKey).matches()) {
            throw invalidKey();
        }
        UUID uuid = UUID.fromString(rawKey);
        if (uuid.equals(NIL) || uuid.equals(MAX)) {
            throw invalidKey();
        }
        return uuid.toString();
    }

    private static ApiException invalidKey() {
        return new ApiException(ErrorCode.VALOR_INVALIDO, KEY_HEADER);
    }
}
