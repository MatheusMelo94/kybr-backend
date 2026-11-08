package com.matheusmelo.kybr.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.support.MovableClock;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryDocumentStorageTest {

    private static final Instant T0 = Instant.parse("2026-11-07T12:00:00Z");

    private final MovableClock clock = new MovableClock(T0);
    private final InMemoryDocumentStorage storage = new InMemoryDocumentStorage(clock, 1024);

    @Test
    void roundTripsAnObjectWithItsTypeAndMetadata() {
        String key = DocumentStorage.key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        storage.put(DocumentStorage.Area.FINAL, key, new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "application/pdf", Map.of("sha256", "ab"));

        assertThat(storage.exists(DocumentStorage.Area.FINAL, key)).isTrue();
        assertThat(storage.object(DocumentStorage.Area.FINAL, key)).hasValueSatisfying(object -> {
            assertThat(object.bytes()).containsExactly(1, 2, 3);
            assertThat(object.contentType()).isEqualTo("application/pdf");
            assertThat(object.metadata()).containsEntry("sha256", "ab");
        });
    }

    @Test
    void deletesAndDeletingAMissingObjectIsFine() {
        String key = DocumentStorage.key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        storage.put(DocumentStorage.Area.FINAL, key, new ByteArrayInputStream(new byte[] {1}), 1, "image/jpeg", Map.of());

        storage.delete(DocumentStorage.Area.FINAL, key);
        storage.delete(DocumentStorage.Area.FINAL, key);

        assertThat(storage.exists(DocumentStorage.Area.FINAL, key)).isFalse();
    }

    @Test
    void listsAWorkspacePrefixInKeyOrderWithSizesAndTimes() {
        UUID workspace = UUID.randomUUID();
        String b = DocumentStorage.key(workspace, UUID.randomUUID(), UUID.fromString("00000000-0000-4000-8000-00000000000b"));
        String a = DocumentStorage.key(workspace, UUID.randomUUID(), UUID.fromString("00000000-0000-4000-8000-00000000000a"));
        storage.put(DocumentStorage.Area.FINAL, b, new ByteArrayInputStream(new byte[2]), 2, "image/png", Map.of());
        clock.advance(Duration.ofHours(1));
        storage.put(DocumentStorage.Area.FINAL, a, new ByteArrayInputStream(new byte[1]), 1, "image/png", Map.of());
        storage.put(DocumentStorage.Area.FINAL, DocumentStorage.key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                new ByteArrayInputStream(new byte[1]), 1, "image/png", Map.of());

        assertThat(storage.list(DocumentStorage.Area.FINAL, DocumentStorage.prefix(workspace)).toList())
                .extracting(StoredObject::key).containsExactlyInAnyOrder(a, b);
        assertThat(storage.list(DocumentStorage.Area.FINAL, DocumentStorage.prefix(workspace)).filter(o -> o.key().equals(b)).findFirst())
                .hasValue(new StoredObject(b, 2, T0));
    }

    @Test
    void keysAreWorkspaceCaseDocument() {
        UUID w = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();

        assertThat(DocumentStorage.key(w, c, d)).isEqualTo(w + "/" + c + "/" + d);
        assertThat(DocumentStorage.prefix(w)).isEqualTo(w + "/");
    }

    @Test
    void refusesAPutPastItsMemoryCapWithATypedFailure() {
        storage.put(DocumentStorage.Area.FINAL, "w/c/1", new ByteArrayInputStream(new byte[1000]), 1000, "image/jpeg", Map.of());

        assertThatThrownBy(() -> storage.put(DocumentStorage.Area.FINAL, "w/c/2", new ByteArrayInputStream(new byte[100]), 100, "image/jpeg",
                Map.of())).isInstanceOf(DocumentStorageException.class);
        assertThat(storage.exists(DocumentStorage.Area.FINAL, "w/c/2")).isFalse();
    }

    @Test
    void theTwoAreasAreSeparateAndOnlyTheQuarantineIsReadBack() throws Exception {
        InMemoryDocumentStorage storage = new InMemoryDocumentStorage(Clock.systemUTC());
        storage.put(DocumentStorage.Area.QUARANTINE, "w/c/d", new ByteArrayInputStream(new byte[] {1, 2}), 2,
                "application/pdf", Map.of());

        assertThat(storage.exists(DocumentStorage.Area.QUARANTINE, "w/c/d")).isTrue();
        assertThat(storage.exists(DocumentStorage.Area.FINAL, "w/c/d")).isFalse();
        assertThat(storage.list(DocumentStorage.Area.FINAL, "")).isEmpty();
        try (var in = storage.get(DocumentStorage.Area.QUARANTINE, "w/c/d")) {
            assertThat(in.readAllBytes()).containsExactly(1, 2);
        }
        assertThatThrownBy(() -> storage.get(DocumentStorage.Area.FINAL, "w/c/d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.get(DocumentStorage.Area.QUARANTINE, "w/c/missing"))
                .isInstanceOf(DocumentStorageException.class);
        storage.delete(DocumentStorage.Area.QUARANTINE, "w/c/d");
        assertThat(storage.exists(DocumentStorage.Area.QUARANTINE, "w/c/d")).isFalse();
    }
}
