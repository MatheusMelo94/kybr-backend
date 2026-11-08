package com.matheusmelo.kybr.service.image;

import java.io.InputStream;

/**
 * Re-encodes a scanned photo that passed the header check, so nothing but its pixels is stored.
 * Implementations: {@link SanitizerClient}, the sanitizer service in the isolated scanner
 * organisation ({@code kybr.image.mode=remote}, required in prod), and
 * {@link MetadataStrippingSanitizer}, a non-production stand-in. Neither decodes in the API.
 * Implementations never throw for a sanitizer problem.
 */
public interface PhotoSanitizer {

    /**
     * @param original the photo as sent, read to the end
     * @param size its length in bytes
     * @param format its format, from the header check
     */
    SanitizeResult sanitize(InputStream original, long size, ImageHeaderCheck.Format format);
}
