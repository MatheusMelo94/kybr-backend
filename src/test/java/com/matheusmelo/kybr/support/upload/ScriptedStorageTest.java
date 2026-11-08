package com.matheusmelo.kybr.support.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.service.storage.DocumentStorage;
import com.matheusmelo.kybr.service.storage.DocumentStorageException;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScriptedStorageTest {

    private final ScriptedStorage storage = new ScriptedStorage();

    @Test
    void unscriptedItStoresAndShowsKeysAndMetadata() {
        put("w/c/1", Map.of("sha256", "aa"));

        assertThat(storage.keys()).containsExactly("w/c/1");
        assertThat(storage.metadata("w/c/1")).containsEntry("sha256", "aa");
        assertThat(storage.contentType("w/c/1")).isEqualTo("image/jpeg");
        assertThat(storage.bytes("w/c/1")).containsExactly(1, 2);
        assertThat(storage.exists(DocumentStorage.Area.FINAL, "w/c/1")).isTrue();
    }

    @Test
    void failsTheNthPutWithATypedFailureAndStoresNothingForIt() {
        storage.failPutOnCall(2);

        put("w/c/1", Map.of());
        assertThatThrownBy(() -> put("w/c/2", Map.of())).isInstanceOf(DocumentStorageException.class);
        put("w/c/3", Map.of());

        assertThat(storage.keys()).containsExactlyInAnyOrder("w/c/1", "w/c/3");
        assertThat(storage.puts()).isEqualTo(3);
    }

    @Test
    void failsTheNthDeleteAndKeepsTheObject() {
        put("w/c/1", Map.of());
        storage.failDeleteOnCall(1);

        assertThatThrownBy(() -> storage.delete(DocumentStorage.Area.FINAL, "w/c/1")).isInstanceOf(DocumentStorageException.class);
        assertThat(storage.exists(DocumentStorage.Area.FINAL, "w/c/1")).isTrue();
        storage.delete(DocumentStorage.Area.FINAL, "w/c/1");
        assertThat(storage.exists(DocumentStorage.Area.FINAL, "w/c/1")).isFalse();
        assertThat(storage.deletes()).isEqualTo(2);
    }

    @Test
    void resetForgetsObjectsScriptsAndCalls() {
        storage.failPutOnCall(1);
        storage.reset();

        put("w/c/1", Map.of());
        storage.reset();

        assertThat(storage.keys()).isEmpty();
        assertThat(storage.puts()).isZero();
    }

    private void put(String key, Map<String, String> metadata) {
        storage.put(DocumentStorage.Area.FINAL, key, new ByteArrayInputStream(new byte[] {1, 2}), 2, "image/jpeg", metadata);
    }

    @Test
    void theNthGetFailsAndQuarantineAndFinalKeysAreSeparate() throws Exception {
        storage.put(DocumentStorage.Area.QUARANTINE, "w/c/q", new java.io.ByteArrayInputStream(new byte[] {1}), 1,
                "application/pdf", java.util.Map.of());
        storage.failGetOnCall(1);

        assertThatThrownBy(() -> storage.get(DocumentStorage.Area.QUARANTINE, "w/c/q"))
                .isInstanceOf(com.matheusmelo.kybr.service.storage.DocumentStorageException.class);
        try (var in = storage.get(DocumentStorage.Area.QUARANTINE, "w/c/q")) {
            assertThat(in.readAllBytes()).containsExactly(1);
        }
        assertThat(storage.quarantineKeys()).containsExactly("w/c/q");
        assertThat(storage.keys()).isEmpty();
    }
}
