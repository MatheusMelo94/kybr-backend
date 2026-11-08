package com.matheusmelo.kybr.support.upload;

import com.matheusmelo.kybr.service.scan.MalwareScanner;
import com.matheusmelo.kybr.service.scan.ScanVerdict;
import com.matheusmelo.kybr.service.scan.StubScanner;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scanner a test can script: infected on the nth file, unavailable, slow, or held at a gate until
 * the test releases it (to line up concurrent uploads). Unscripted, it answers like the stub. It
 * reads every byte, like the real one. Calls count from 1 after construction or {@link #reset()}.
 * Made the primary scanner by {@link UploadTestDoublesConfiguration}.
 */
public class ScriptedScanner implements MalwareScanner {

    private final StubScanner stub = new StubScanner();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Long> sizes = new ArrayList<>();
    private final Map<Integer, String> infectedOn = new ConcurrentHashMap<>();
    private volatile boolean unavailable;
    private volatile Duration delay = Duration.ZERO;
    private volatile Gate gate;
    private final AtomicInteger pings = new AtomicInteger();
    private volatile int readyAfterPings = 1;

    @Override
    public ScanVerdict scan(InputStream content, long size) {
        int call = calls.incrementAndGet();
        byte[] bytes;
        try {
            bytes = content.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        synchronized (sizes) {
            sizes.add(size);
        }
        Gate current = gate;
        if (current != null) {
            current.pass();
        }
        pause(delay);
        if (unavailable) {
            return new ScanVerdict.Unavailable();
        }
        String signature = infectedOn.get(call);
        if (signature != null) {
            return new ScanVerdict.Infected(signature);
        }
        return stub.scan(new ByteArrayInputStream(bytes), size);
    }

    /** Ready from the {@link #coldStart}-scripted ping on (the first, unscripted). */
    @Override
    public boolean ping() {
        return pings.incrementAndGet() >= readyAfterPings;
    }

    /** A stopped scanner: it answers ready only from its {@code nth} ping (from 1). */
    public void coldStart(int nth) {
        readyAfterPings = nth;
    }

    /** A scanner that never wakes up. */
    public void neverWakes() {
        readyAfterPings = Integer.MAX_VALUE;
    }

    public int pings() {
        return pings.get();
    }

    /** The {@code call}th scan (from 1) reports {@code signature}. */
    public void infectedOnCall(int call, String signature) {
        infectedOn.put(call, signature);
    }

    /** Every scan is unavailable until {@link #reset()}. */
    public void unavailable() {
        unavailable = true;
    }

    /** Every scan waits this long before answering. */
    public void delay(Duration value) {
        delay = value;
    }

    /** Every scan from now on waits at the returned gate until it is released. */
    public Gate holdScans() {
        Gate held = new Gate();
        gate = held;
        return held;
    }

    public int calls() {
        return calls.get();
    }

    /** The declared size of every scan so far, in order. */
    public List<Long> scannedSizes() {
        synchronized (sizes) {
            return List.copyOf(sizes);
        }
    }

    public void reset() {
        Gate held = gate;
        if (held != null) {
            held.release();
        }
        gate = null;
        calls.set(0);
        synchronized (sizes) {
            sizes.clear();
        }
        infectedOn.clear();
        pings.set(0);
        readyAfterPings = 1;
        unavailable = false;
        delay = Duration.ZERO;
    }

    private static void pause(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Holds scans until released; a scan never waits longer than 30 s, so a broken test can't hang. */
    public static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger waiting = new AtomicInteger();

        void pass() {
            waiting.incrementAndGet();
            entered.countDown();
            try {
                released.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                waiting.decrementAndGet();
            }
        }

        /** Whether a scan reached the gate within {@code timeout}. */
        public boolean awaitEntered(Duration timeout) throws InterruptedException {
            return entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** Scans currently held. */
        public int waiting() {
            return waiting.get();
        }

        public void release() {
            released.countDown();
        }
    }
}
