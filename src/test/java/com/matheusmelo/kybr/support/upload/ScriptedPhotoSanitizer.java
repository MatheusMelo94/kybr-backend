package com.matheusmelo.kybr.support.upload;

import com.matheusmelo.kybr.service.image.ImageHeaderCheck;
import com.matheusmelo.kybr.service.image.MetadataStrippingSanitizer;
import com.matheusmelo.kybr.service.image.PhotoSanitizer;
import com.matheusmelo.kybr.service.image.SanitizeResult;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A photo sanitizer a test can script: every call refused, unavailable, timed out or throwing.
 * Unscripted, it answers like the in-process stand-in. Made the primary sanitizer by
 * {@link UploadTestDoublesConfiguration}; reset it between tests.
 */
public class ScriptedPhotoSanitizer implements PhotoSanitizer {

    private final MetadataStrippingSanitizer standIn = new MetadataStrippingSanitizer();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile SanitizeResult scripted;
    private volatile RuntimeException failure;

    @Override
    public SanitizeResult sanitize(InputStream original, long size, ImageHeaderCheck.Format format) {
        calls.incrementAndGet();
        RuntimeException thrown = failure;
        if (thrown != null) {
            throw thrown;
        }
        SanitizeResult answer = scripted;
        return answer != null ? answer : standIn.sanitize(original, size, format);
    }

    /** Every call answers {@code result} until {@link #reset()}. */
    public void answering(SanitizeResult result) {
        scripted = result;
    }

    /** Every call throws {@code error} until {@link #reset()}: an unexpected failure mid-upload. */
    public void throwing(RuntimeException error) {
        failure = error;
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        scripted = null;
        failure = null;
        calls.set(0);
    }
}
