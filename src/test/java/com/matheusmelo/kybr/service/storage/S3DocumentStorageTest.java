package com.matheusmelo.kybr.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.S3Config;
import com.matheusmelo.kybr.support.upload.StubS3Server;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sts.auth.StsWebIdentityTokenFileCredentialsProvider;

/** The S3 client: generated keys, SSE-KMS on every put, least-privilege calls, and failures without secrets. */
@ExtendWith(OutputCaptureExtension.class)
class S3DocumentStorageTest {

    private static final String BUCKET = "kybr-docs-test";
    private static final String KMS_KEY = "arn:aws:kms:sa-east-1:000000000000:key/00000000-0000-4000-8000-000000000000";
    private static final String QUARANTINE = "kybr-quarantine-test";
    private static final String QUARANTINE_KEY =
            "arn:aws:kms:sa-east-1:000000000000:key/00000000-0000-4000-8000-000000000001";
    private static final String ACCESS_KEY = "test-only-access-key-id";
    private static final String SECRET_KEY = "test-only-secret-access-key-0000000000000";

    private final StubS3Server s3 = new StubS3Server();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final KybrProperties.Storage settings = new KybrProperties.Storage(KybrProperties.Storage.Mode.S3, BUCKET,
            "sa-east-1", KMS_KEY, QUARANTINE, QUARANTINE_KEY, null, "sts.amazonaws.com", ACCESS_KEY, SECRET_KEY, s3.endpoint(),
            Duration.ofSeconds(2), Duration.ofSeconds(5));
    private final S3DocumentStorage storage = new S3DocumentStorage(S3Config.s3Client(settings),
            new S3DocumentStorage.Bucket(BUCKET, KMS_KEY), new S3DocumentStorage.Bucket(QUARANTINE, QUARANTINE_KEY),
            meters);

    @AfterEach
    void stop() {
        s3.close();
    }

    private static String key() {
        return DocumentStorage.key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private double calls(String operacao, String resultado) {
        var counter = meters.find(S3DocumentStorage.METRIC).tag("operacao", operacao).tag("resultado", resultado)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aPutSendsTheObjectUnderItsGeneratedKeyEncryptedWithTheConfiguredKmsKey() {
        String key = key();
        byte[] content = {1, 2, 3, 4, 5};

        storage.put(DocumentStorage.Area.FINAL, key, new ByteArrayInputStream(content), content.length, "image/jpeg", Map.of("sha256", "ab"));

        StubS3Server.Recorded put = s3.requests().getFirst();
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.path()).isEqualTo("/" + BUCKET + "/" + key);
        assertThat(put.header("x-amz-server-side-encryption")).isEqualTo("aws:kms");
        assertThat(put.header("x-amz-server-side-encryption-aws-kms-key-id")).isEqualTo(KMS_KEY);
        assertThat(put.header("content-type")).isEqualTo("image/jpeg");
        assertThat(put.header("x-amz-meta-sha256")).isEqualTo("ab");
        assertThat(calls("put", "ok")).isEqualTo(1);
    }

    @Test
    void aDeleteOfAMissingObjectSucceeds() {
        storage.delete(DocumentStorage.Area.FINAL, key());

        assertThat(s3.requests().getFirst().method()).isEqualTo("DELETE");
        assertThat(calls("delete", "ok")).isEqualTo(1);
    }

    @Test
    void existenceAndListingUseTheListCallOnly() {
        UUID workspace = UUID.randomUUID();
        String held = DocumentStorage.key(workspace, UUID.randomUUID(), UUID.randomUUID());
        String other = DocumentStorage.key(workspace, UUID.randomUUID(), UUID.randomUUID());
        s3.holding(held, other);

        assertThat(storage.exists(DocumentStorage.Area.FINAL, held)).isTrue();
        assertThat(storage.exists(DocumentStorage.Area.FINAL, held.substring(0, held.length() - 1))).isFalse();
        List<StoredObject> listed = storage.list(DocumentStorage.Area.FINAL, DocumentStorage.prefix(workspace)).toList();

        assertThat(listed).extracting(StoredObject::key).containsExactly(held, other);
        assertThat(listed.getFirst().size()).isEqualTo(3);
        assertThat(listed.getFirst().lastModified()).isEqualTo(Instant.parse("2026-11-07T12:00:00Z"));
        assertThat(s3.requests()).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.query()).contains("list-type=2");
        });
    }

    @Test
    void theFinalBucketOnlyEverGetsPutDeleteAndList() {
        String key = key();
        s3.holding(key);

        storage.put(DocumentStorage.Area.FINAL, key, new ByteArrayInputStream(new byte[] {1}), 1, "application/pdf", Map.of());
        storage.exists(DocumentStorage.Area.FINAL, key);
        storage.list(DocumentStorage.Area.FINAL, "").toList();
        storage.delete(DocumentStorage.Area.FINAL, key);

        assertThat(s3.requests()).allSatisfy(request -> {
            boolean list = request.method().equals("GET") && request.query() != null
                    && request.query().contains("list-type=2");
            assertThat(request.method().equals("PUT") || request.method().equals("DELETE") || list)
                    .as("%s %s?%s", request.method(), request.path(), request.query()).isTrue();
        });
        assertThat(s3.requests()).filteredOn(request -> request.method().equals("PUT"))
                .allSatisfy(put -> assertThat(put.header("x-amz-server-side-encryption-aws-kms-key-id"))
                        .isEqualTo(KMS_KEY));
    }

    @Test
    void theQuarantineIsItsOwnBucketEncryptedWithItsOwnKeyAndTheOnlyOneReadBack() throws Exception {
        String key = key();

        storage.put(DocumentStorage.Area.QUARANTINE, key, new ByteArrayInputStream(new byte[] {1, 2}), 2,
                "application/pdf", Map.of());
        byte[] read;
        try (var in = storage.get(DocumentStorage.Area.QUARANTINE, key)) {
            read = in.readAllBytes();
        }
        storage.delete(DocumentStorage.Area.QUARANTINE, key);

        assertThat(read).containsExactly(StubS3Server.OBJECT_BYTES);
        assertThat(s3.requests()).allSatisfy(request -> assertThat(request.path()).isEqualTo("/" + QUARANTINE + "/" + key));
        assertThat(s3.requests().getFirst().header("x-amz-server-side-encryption-aws-kms-key-id"))
                .isEqualTo(QUARANTINE_KEY);
        assertThat(s3.requests()).extracting(StubS3Server.Recorded::method).containsExactly("PUT", "GET", "DELETE");
        assertThatThrownBy(() -> storage.get(DocumentStorage.Area.FINAL, key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(s3.requests()).hasSize(3);
    }

    @Test
    void aStorageErrorIsATypedFailureCarryingTheStatusAndNoSecretOrBody(CapturedOutput output) {
        s3.failWith(403);

        assertThatThrownBy(() -> storage.put(DocumentStorage.Area.FINAL, key(), new ByteArrayInputStream(new byte[] {1}), 1, "image/png", Map.of()))
                .isInstanceOfSatisfying(DocumentStorageException.class, e -> {
                    assertThat(e.getMessage()).contains("put").contains("403")
                            .doesNotContain(StubS3Server.SECRET_BODY_MARKER).doesNotContain(ACCESS_KEY)
                            .doesNotContain(SECRET_KEY);
                    assertThat(e.getCause()).isNull();
                });
        assertThatThrownBy(() -> storage.delete(DocumentStorage.Area.FINAL, key())).isInstanceOf(DocumentStorageException.class);
        assertThatThrownBy(() -> storage.list(DocumentStorage.Area.FINAL, "").toList()).isInstanceOf(DocumentStorageException.class);

        assertThat(output.getAll()).doesNotContain(StubS3Server.SECRET_BODY_MARKER).doesNotContain(SECRET_KEY)
                .doesNotContain(ACCESS_KEY);
        assertThat(calls("put", "erro")).isEqualTo(1);
    }

    @Test
    void anUnreachableEndpointIsATypedFailure() {
        s3.close();

        assertThatThrownBy(() -> storage.delete(DocumentStorage.Area.FINAL, key())).isInstanceOf(DocumentStorageException.class);
    }

    @Test
    void credentialsComeFromTheWebIdentityRoleUnlessStaticKeysAreSet() {
        KybrProperties.Storage withRole = new KybrProperties.Storage(KybrProperties.Storage.Mode.S3, BUCKET,
                "sa-east-1", KMS_KEY, QUARANTINE, QUARANTINE_KEY, "arn:aws:iam::000000000000:role/kybr-api", "sts.amazonaws.com", null, null, null,
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThat(S3Config.credentials(withRole)).isInstanceOf(StsWebIdentityTokenFileCredentialsProvider.class);
        assertThat(S3Config.credentials(settings)).isInstanceOf(StaticCredentialsProvider.class);
    }

    @Test
    void theSettingsNeverPrintTheStaticKeys() {
        assertThat(settings.toString()).doesNotContain(ACCESS_KEY).doesNotContain(SECRET_KEY);
    }
}
