package com.matheusmelo.kybr.support.upload;

import com.matheusmelo.kybr.service.storage.DocumentStorage;
import com.matheusmelo.kybr.service.storage.DocumentStorageException;
import com.matheusmelo.kybr.service.storage.InMemoryDocumentStorage;
import com.matheusmelo.kybr.service.storage.StoredObject;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Storage a test can script: the nth put, get or delete fails with the port's typed failure (and changes
 * nothing). Unscripted, it keeps objects in memory, and a test can inspect every stored key, its
 * bytes, type and metadata. Calls count from 1 after construction or {@link #reset()}. Made the
 * primary storage by {@link UploadTestDoublesConfiguration}.
 */
public class ScriptedStorage implements DocumentStorage {

    private final InMemoryDocumentStorage objects;
    private final AtomicInteger puts = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private final AtomicInteger gets = new AtomicInteger();
    private final Set<Integer> failingGets = ConcurrentHashMap.newKeySet();
    private volatile ScriptedScanner.Gate gate;
    private volatile RuntimeException putFailure;
    private final Set<Integer> failingPuts = ConcurrentHashMap.newKeySet();
    private final Set<Integer> failingDeletes = ConcurrentHashMap.newKeySet();

    public ScriptedStorage() {
        this(Clock.systemUTC());
    }

    /** @param clock stamps each object's last-modified time (the orphan check's age) */
    public ScriptedStorage(Clock clock) {
        this.objects = new InMemoryDocumentStorage(clock);
    }

    @Override
    public void put(Area area, String key, InputStream content, long size, String contentType,
            Map<String, String> metadata) {
        if (failingPuts.contains(puts.incrementAndGet())) {
            throw new DocumentStorageException("put failed: scripted");
        }
        ScriptedScanner.Gate current = gate;
        if (current != null) {
            current.pass();
        }
        RuntimeException thrown = putFailure;
        if (thrown != null) {
            throw thrown;
        }
        objects.put(area, key, content, size, contentType, metadata);
    }

    @Override
    public InputStream get(Area area, String key) {
        if (failingGets.contains(gets.incrementAndGet())) {
            throw new DocumentStorageException("get failed: scripted");
        }
        return objects.get(area, key);
    }

    @Override
    public void delete(Area area, String key) {
        if (failingDeletes.contains(deletes.incrementAndGet())) {
            throw new DocumentStorageException("delete failed: scripted");
        }
        objects.delete(area, key);
    }

    @Override
    public boolean exists(Area area, String key) {
        return objects.exists(area, key);
    }

    @Override
    public Stream<StoredObject> list(Area area, String prefix) {
        return objects.list(area, prefix);
    }

    /** Every put from now on waits at the returned gate until it is released (to line up concurrent uploads). */
    public ScriptedScanner.Gate holdPuts() {
        ScriptedScanner.Gate held = new ScriptedScanner.Gate();
        gate = held;
        return held;
    }

    /** Every put throws {@code error} until {@link #reset()}: an unexpected failure mid-upload. */
    public void throwingOnPut(RuntimeException error) {
        putFailure = error;
    }

    /** The {@code call}th get (from 1) fails. */
    public void failGetOnCall(int call) {
        failingGets.add(call);
    }

    /** The {@code call}th put (from 1) fails. */
    public void failPutOnCall(int call) {
        failingPuts.add(call);
    }

    /** The {@code call}th delete (from 1) fails. */
    public void failDeleteOnCall(int call) {
        failingDeletes.add(call);
    }

    /** Every key of an accepted (final) object, in order. */
    public List<String> keys() {
        return keys(Area.FINAL);
    }

    /** Every key still in quarantine, in order. */
    public List<String> quarantineKeys() {
        return keys(Area.QUARANTINE);
    }

    public List<String> keys(Area area) {
        return objects.list(area, "").map(StoredObject::key).toList();
    }

    /** An accepted (final) object's bytes. */
    public byte[] bytes(String key) {
        return blob(Area.FINAL, key).bytes();
    }

    public byte[] bytes(Area area, String key) {
        return blob(area, key).bytes();
    }

    public String contentType(String key) {
        return blob(Area.FINAL, key).contentType();
    }

    public Map<String, String> metadata(String key) {
        return blob(Area.FINAL, key).metadata();
    }

    public int puts() {
        return puts.get();
    }

    public int gets() {
        return gets.get();
    }

    public int deletes() {
        return deletes.get();
    }

    public void reset() {
        ScriptedScanner.Gate held = gate;
        if (held != null) {
            held.release();
        }
        gate = null;
        putFailure = null;
        objects.clear();
        puts.set(0);
        deletes.set(0);
        gets.set(0);
        failingGets.clear();
        failingPuts.clear();
        failingDeletes.clear();
    }

    private InMemoryDocumentStorage.Blob blob(Area area, String key) {
        return objects.object(area, key).orElseThrow(() -> new AssertionError("no " + area + " object " + key));
    }
}
