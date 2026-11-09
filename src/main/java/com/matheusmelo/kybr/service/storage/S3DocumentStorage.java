package com.matheusmelo.kybr.service.storage;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * Seller documents in two private S3 buckets ({@code kybr.storage.mode=s3}): the quarantine, where
 * received files wait for the scan job, and the final bucket. Each bucket's objects are encrypted
 * under its own customer-managed KMS key. On the final bucket only {@code PutObject},
 * {@code DeleteObject} and {@code ListObjectsV2} are ever called (existence is a one-key listing),
 * matching the API role's permissions there; {@code GetObject} goes to the quarantine only, for the
 * scan job.
 *
 * <p>A failure is a {@link DocumentStorageException} carrying the operation and the HTTP status
 * only, and is logged the same way: never a response body (which can echo request details), a
 * credential or the SDK's own message. Calls are counted on {@value #METRIC} by {@code operacao}
 * and {@code resultado} ({@code ok}, {@code erro}).
 */
public class S3DocumentStorage implements DocumentStorage {

    public static final String METRIC = "kybr.storage.calls";
    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);

    /** One bucket and the KMS key its objects are encrypted under. */
    public record Bucket(String name, String kmsKeyId) {
    }

    private final S3Client s3;
    private final Bucket finalBucket;
    private final Bucket quarantine;
    private final MeterRegistry meters;

    public S3DocumentStorage(S3Client s3, Bucket finalBucket, Bucket quarantine, MeterRegistry meters) {
        this.s3 = s3;
        this.finalBucket = finalBucket;
        this.quarantine = quarantine;
        this.meters = meters;
    }

    @Override
    public void put(Area area, String key, InputStream content, long size, String contentType,
            Map<String, String> metadata) {
        Bucket bucket = bucket(area);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket.name())
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .ssekmsKeyId(bucket.kmsKeyId())
                .bucketKeyEnabled(true)
                .metadata(metadata)
                .build();
        call("put", () -> s3.putObject(request, RequestBody.fromInputStream(content, size)));
    }

    @Override
    public InputStream get(Area area, String key) {
        DocumentStorage.requireQuarantine(area);
        return call("get", () -> s3.getObject(GetObjectRequest.builder().bucket(quarantine.name()).key(key).build()));
    }

    @Override
    public void delete(Area area, String key) {
        call("delete", () -> s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket(area).name()).key(key)
                .build()));
    }

    @Override
    public boolean exists(Area area, String key) {
        return call("exists", () -> s3.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(bucket(area).name()).prefix(key).maxKeys(1).build()))
                .contents().stream().anyMatch(object -> object.key().equals(key));
    }

    /** Pages through the listing lazily; a failure on any page throws {@link DocumentStorageException}. */
    @Override
    public Stream<StoredObject> list(Area area, String prefix) {
        ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket(area).name()).prefix(prefix)
                .build();
        Iterator<StoredObject> objects = new Iterator<>() {
            private Iterator<S3Object> pages;
            private boolean finished;

            @Override
            public boolean hasNext() {
                if (pages == null) {
                    pages = translated("list", () -> s3.listObjectsV2Paginator(request).contents().iterator());
                }
                boolean more = translated("list", pages::hasNext);
                if (!more && !finished) {
                    finished = true;
                    count("list", "ok");
                }
                return more;
            }

            @Override
            public StoredObject next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                S3Object object = translated("list", pages::next);
                return new StoredObject(object.key(), object.size(), object.lastModified());
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(objects, Spliterator.ORDERED), false);
    }

    private Bucket bucket(Area area) {
        return area == Area.QUARANTINE ? quarantine : finalBucket;
    }

    private <T> T call(String operacao, Supplier<T> work) {
        T result = translated(operacao, work);
        count(operacao, "ok");
        return result;
    }

    /** Runs {@code work}, turning an SDK failure into a counted, logged {@link DocumentStorageException}. */
    private <T> T translated(String operacao, Supplier<T> work) {
        try {
            return work.get();
        } catch (SdkException e) {
            count(operacao, "erro");
            String status = e instanceof AwsServiceException service ? String.valueOf(service.statusCode()) : "none";
            log.warn("event=storage_error operacao={} error={} status={}", operacao, e.getClass().getSimpleName(), status);
            throw new DocumentStorageException("document storage " + operacao + " failed (status " + status + ")");
        }
    }

    private void count(String operacao, String resultado) {
        meters.counter(METRIC, "operacao", operacao, "resultado", resultado).increment();
    }
}
