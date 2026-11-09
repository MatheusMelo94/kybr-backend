package com.matheusmelo.kybr.service.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The storage stand-in ({@code kybr.storage.mode=memory}: local, docker, test and staging by
 * default). Objects live in this process until it stops, up to a total byte cap; past it a put fails
 * like an unreachable store would. Never in prod (the bean doesn't exist there, and the prod guard
 * refuses the mode).
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "kybr.storage", name = "mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryDocumentStorage implements DocumentStorage {

    /** Enough for any test or staging session; a staging machine must not run out of memory. */
    static final long DEFAULT_MAX_TOTAL_BYTES = 128L * 1024 * 1024;

    /** One stored object. */
    public record Blob(byte[] bytes, String contentType, Map<String, String> metadata, Instant lastModified) {
        public Blob {
            metadata = Map.copyOf(metadata);
        }
    }

    private final Clock clock;
    private final long maxTotalBytes;
    private final Map<Area, Map<String, Blob>> objects = new EnumMap<>(Map.of(
            Area.QUARANTINE, new ConcurrentHashMap<>(), Area.FINAL, new ConcurrentHashMap<>()));

    @Autowired
    public InMemoryDocumentStorage(Clock clock) {
        this(clock, DEFAULT_MAX_TOTAL_BYTES);
    }

    InMemoryDocumentStorage(Clock clock, long maxTotalBytes) {
        this.clock = clock;
        this.maxTotalBytes = maxTotalBytes;
    }

    @Override
    public synchronized void put(Area area, String key, InputStream content, long size, String contentType,
            Map<String, String> metadata) {
        byte[] bytes;
        try {
            bytes = content.readAllBytes();
        } catch (IOException e) {
            throw new DocumentStorageException("put failed: content unreadable", e);
        }
        long others = objects.entrySet().stream().flatMap(byArea -> byArea.getValue().entrySet().stream()
                        .filter(entry -> byArea.getKey() != area || !entry.getKey().equals(key)))
                .mapToLong(entry -> entry.getValue().bytes().length).sum();
        if (others + bytes.length > maxTotalBytes) {
            throw new DocumentStorageException("put failed: in-memory storage is full");
        }
        objects.get(area).put(key, new Blob(bytes, contentType, metadata, clock.instant()));
    }

    @Override
    public InputStream get(Area area, String key) {
        DocumentStorage.requireQuarantine(area);
        Blob blob = objects.get(area).get(key);
        if (blob == null) {
            throw new DocumentStorageException("get failed: no such object");
        }
        return new ByteArrayInputStream(blob.bytes());
    }

    @Override
    public void delete(Area area, String key) {
        objects.get(area).remove(key);
    }

    @Override
    public boolean exists(Area area, String key) {
        return objects.get(area).containsKey(key);
    }

    @Override
    public Stream<StoredObject> list(Area area, String prefix) {
        List<StoredObject> listed = objects.get(area).entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(entry -> new StoredObject(entry.getKey(), entry.getValue().bytes().length,
                        entry.getValue().lastModified()))
                .sorted(Comparator.comparing(StoredObject::key))
                .toList();
        return listed.stream();
    }

    /** The stored object, for tests and local inspection. */
    public Optional<Blob> object(Area area, String key) {
        return Optional.ofNullable(objects.get(area).get(key));
    }

    /** Forgets every object. */
    public void clear() {
        objects.values().forEach(Map::clear);
    }
}
